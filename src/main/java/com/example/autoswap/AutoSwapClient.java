package com.example.autoswap;

import com.example.autoswap.mixin.HandledScreenAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class AutoSwapClient implements ClientModInitializer {

    private static final String CATEGORY = "category.autoswap";
    private static final long COOLDOWN_MS = 50;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int GREEN = 0xFF55FF55;
    private static final int YELLOW = 0xFFFFFF55;

    /** Пара предметов на одной кнопке: A <-> B в левой руке. */
    private static class Pair {
        final String a, b;
        int key;
        boolean wasDown = false;

        Pair(String a, String b, int key) {
            this.a = a;
            this.b = b;
            this.key = key;
        }
    }

    private final List<Pair> pairs = new ArrayList<>();

    private KeyBinding selectKey;
    private String firstItem = null;   // выбран первый предмет пары
    private Pair bindingPair = null;   // пара создана, ждём клавишу для бинда
    private long lastSwap = 0;
    private Path configFile;

    @Override
    public void onInitializeClient() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve("autoswap.txt");
        load();

        selectKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autoswap.select", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, CATEGORY));

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof InventoryScreen inv)) return;

            // Режим назначения клавиши: перехватываем нажатие до инвентаря
            ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, mods) -> {
                if (bindingPair == null) return true;
                if (key == GLFW.GLFW_KEY_ESCAPE) {
                    bindingPair = null;
                    firstItem = null;
                    msg(client, "AutoSwap: отменено");
                    return true;
                }
                assignKey(client, key);
                return false;
            });

            // V = выбрать предмет, Shift+V = удалить пары с этим предметом
            ScreenKeyboardEvents.afterKeyPress(screen).register((s, key, scancode, mods) -> {
                if (!selectKey.matchesKey(key, scancode)) return;
                if ((mods & GLFW.GLFW_MOD_SHIFT) != 0) removeHovered(client, inv);
                else selectHovered(client, inv);
            });

            // Панель с биндами рисуем и поверх инвентаря
            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> drawPanel(ctx, client));

            // Закрыл инвентарь, не закончив выбор пары: сбрасываем, чтобы следующая клавиша не стала биндом
            ScreenEvents.remove(screen).register(s -> {
                firstItem = null;
                bindingPair = null;
            });
        });

        // Панель в игре
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.currentScreen == null && !mc.options.hudHidden) drawPanel(ctx, mc);
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    // ---------- Тик: бинды ----------

    private void onTick(MinecraftClient mc) {
        if (mc.player == null || mc.interactionManager == null) return;

        // Свап работает без экрана и с открытым инвентарём игрока
        boolean allowed = mc.currentScreen == null || mc.currentScreen instanceof InventoryScreen;
        long handle = mc.getWindow().getHandle();

        for (Pair p : pairs) {
            boolean down = p.key != -1 && InputUtil.isKeyPressed(handle, p.key);
            if (allowed && down && !p.wasDown) swapPair(mc, p);
            p.wasDown = down;
        }
    }

    private void swapPair(MinecraftClient mc, Pair p) {
        long now = System.currentTimeMillis();
        if (now - lastSwap < COOLDOWN_MS) return;

        ItemStack off = mc.player.getOffHandStack();
        String cur = off.isEmpty() ? "" : nameOf(off);

        // Если в руке A, берём B; если B, берём A; иначе пробуем A, потом B
        String[] order = cur.equalsIgnoreCase(p.a) ? new String[]{p.b}
                : cur.equalsIgnoreCase(p.b) ? new String[]{p.a}
                : new String[]{p.a, p.b};

        for (String target : order) {
            int slotId = findSlotId(mc, target);
            if (slotId == -1) continue;

            // SWAP + кнопка 40 = обмен слота с левой рукой
            mc.interactionManager.clickSlot(
                    mc.player.playerScreenHandler.syncId,
                    slotId, 40, SlotActionType.SWAP, mc.player);
            lastSwap = now;
            return;
        }
        msg(mc, "AutoSwap: предметы пары не найдены");
    }

    // ---------- Выбор предметов в инвентаре ----------

    private void selectHovered(MinecraftClient mc, InventoryScreen screen) {
        Slot slot = ((HandledScreenAccessor) screen).getFocusedSlot();
        if (slot == null || !slot.hasStack()) return;
        String name = nameOf(slot.getStack());

        if (firstItem == null) {
            firstItem = name;
            msg(mc, "AutoSwap: выбран " + name + ". Наведи на второй предмет и нажми кнопку выбора");
        } else if (firstItem.equalsIgnoreCase(name)) {
            firstItem = null;
            msg(mc, "AutoSwap: отменено");
        } else {
            bindingPair = new Pair(firstItem, name, -1);
            firstItem = null;
            msg(mc, "AutoSwap: нажми клавишу для бинда (Esc - отмена)");
        }
    }

    private void assignKey(MinecraftClient mc, int key) {
        if (key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT
                || key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL
                || key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_
