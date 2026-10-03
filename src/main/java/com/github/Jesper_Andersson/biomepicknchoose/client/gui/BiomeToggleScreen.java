package com.github.Jesper_Andersson.biomepicknchoose.client.gui;

import com.github.Jesper_Andersson.biomepicknchoose.Config;
import com.github.Jesper_Andersson.biomepicknchoose.client.preview.BiomePreviews;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * On/off toggles for every known overworld biome, one tab per mod. Saved to the common config on Done.
 */
public class BiomeToggleScreen extends Screen {
    private static final int FOOTER_HEIGHT = 56;
    private static final int TAB_HEADER_HEIGHT = 28;
    private static final int ROW_WIDTH = 310;
    private static final int NARROW_ROW_WIDTH = 250;
    private static final int PANEL_MIN_SCREEN_WIDTH = 560;
    private static final int PANEL_TEXT_HEIGHT = 26;
    private static final int PANEL_BUTTON_HEIGHT = 24;
    private static final int HINT_COLOR = 0xA0A0A0;
    private static final Component ON = CommonComponents.OPTION_ON.copy().withStyle(ChatFormatting.GREEN);
    private static final Component OFF = CommonComponents.OPTION_OFF.copy().withStyle(ChatFormatting.RED);

    private final Screen parent;
    private final Set<String> disabled;
    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    private TabNavigationBar tabNavigationBar;
    private Tab[] tabs = new Tab[0];
    private Button presetsButton;
    private Button doneButton;
    private Button cancelButton;
    private Button removePreview;
    // Biome the remove button applies to, set while it is shown
    @Nullable
    private ResourceLocation removeTarget;
    // Restored when the widgets are rebuilt after loading a preset
    private int selectedTab;
    // Shared by all tabs
    private SortMode sortMode = SortMode.ALPHABETICAL;
    @Nullable
    private ResourceLocation lastShownId;
    private boolean hasKnownBiomes;
    // Preview image area, or null when the screen is too narrow for the side panel
    @Nullable
    private ScreenRectangle preview;

    public BiomeToggleScreen(Screen parent) {
        super(Component.translatable("biomepicknchoose.configuration.title"));
        this.parent = parent;
        this.disabled = new HashSet<>(Config.DISABLED_BIOMES.get());
    }

    @Override
    protected void init() {
        hasKnownBiomes = Files.isRegularFile(BiomeToggles.knownBiomesFile());

        Map<String, List<ResourceLocation>> byNamespace = new TreeMap<>(
                Comparator.comparingInt(BiomeToggleScreen::namespaceOrder).thenComparing(Comparator.naturalOrder()));
        for (ResourceLocation id : BiomeToggles.knownBiomes()) {
            byNamespace.computeIfAbsent(id.getNamespace(), ns -> new ArrayList<>()).add(id);
        }
        tabs = byNamespace.entrySet().stream()
                .map(entry -> new BiomeTab(tabTitle(entry.getKey()), entry.getValue()))
                .toArray(Tab[]::new);

        tabNavigationBar = TabNavigationBar.builder(tabManager, width).addTabs(tabs).build();
        addRenderableWidget(tabNavigationBar);
        presetsButton = addRenderableWidget(Button.builder(Component.translatable("biomepicknchoose.configuration.presets"), button -> openPresets()).width(100).build());
        doneButton = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> save()).width(100).build());
        cancelButton = addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).width(100).build());
        removePreview = addRenderableWidget(Button.builder(Component.translatable("biomepicknchoose.configuration.preview.remove"), button -> confirmRemovePreview()).width(120).build());
        removePreview.visible = false;
        int tab = selectedTab < tabs.length ? selectedTab : 0;
        tabNavigationBar.selectTab(tab, false);
        if (lastShownId != null && tabs.length > 0 && tabs[tab] instanceof BiomeTab biomeTab) biomeTab.list.show(lastShownId);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (tabNavigationBar == null) return;
        tabNavigationBar.setWidth(width);
        tabNavigationBar.arrangeElements();
        int top = tabNavigationBar.getRectangle().bottom();
        int areaHeight = height - FOOTER_HEIGHT - top;
        preview = null;
        if (width >= PANEL_MIN_SCREEN_WIDTH) {
            int half = width / 2;
            int previewWidth = Math.min(half - 30, (areaHeight - 8 - PANEL_TEXT_HEIGHT - PANEL_BUTTON_HEIGHT) * 16 / 9);
            if (previewWidth > 0) preview = new ScreenRectangle(half + 10, top + 4, previewWidth, previewWidth * 9 / 16);
        }
        tabManager.setTabArea(new ScreenRectangle(0, top, width, areaHeight));
        presetsButton.setPosition(width / 2 - 155, height - 28);
        doneButton.setPosition(width / 2 - 50, height - 28);
        cancelButton.setPosition(width / 2 + 55, height - 28);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return tabNavigationBar.keyPressed(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        BiomeRow row = preview != null && tabManager.getCurrentTab() instanceof BiomeTab tab ? tab.list.previewBiome() : null;
        // Only for captured pictures: a shipped picture would still show after removing the file
        removeTarget = row != null && BiomePreviews.isCaptured(row.id) ? row.id : null;
        removePreview.visible = removeTarget != null;
        removePreview.active = removeTarget != null;
        if (removeTarget != null) removePreview.setPosition(preview.left(), preview.bottom() + PANEL_TEXT_HEIGHT + 2);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        if (row != null) renderPreview(guiGraphics, row);
        int y = height - FOOTER_HEIGHT + 6;
        guiGraphics.drawCenteredString(font, Component.translatable("biomepicknchoose.configuration.hint.apply"), width / 2, y, HINT_COLOR);
        if (!hasKnownBiomes) {
            guiGraphics.drawCenteredString(font, Component.translatable("biomepicknchoose.configuration.hint.load_world"), width / 2, y + 11, HINT_COLOR);
        }
    }

    private void renderPreview(GuiGraphics guiGraphics, BiomeRow row) {
        int x = preview.left();
        int y = preview.top();
        int w = preview.width();
        int h = preview.height();
        guiGraphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000 | HINT_COLOR);
        ResourceLocation texture = BiomePreviews.textureFor(row.id);
        if (texture != null) {
            guiGraphics.blit(texture, x, y, 0, 0, w, h, w, h);
        } else {
            guiGraphics.fill(x, y, x + w, y + h, 0xFF101010);
            List<FormattedCharSequence> lines = font.split(Component.translatable("biomepicknchoose.configuration.preview.none"), w - 16);
            int lineY = y + (h - lines.size() * font.lineHeight) / 2;
            for (FormattedCharSequence line : lines) {
                guiGraphics.drawCenteredString(font, line, x + w / 2, lineY, HINT_COLOR);
                lineY += font.lineHeight;
            }
        }
        guiGraphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(row.name, w)), x, y + h + 5, 0xFFFFFF);
        guiGraphics.drawString(font, row.id.toString(), x, y + h + 16, HINT_COLOR);
    }

    @Override
    public void removed() {
        BiomePreviews.release();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private void confirmRemovePreview() {
        ResourceLocation id = removeTarget;
        if (id == null) return;
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) BiomePreviews.delete(id);
            minecraft.setScreen(this);
        }, title, Component.translatable("biomepicknchoose.configuration.preview.remove.confirm", id.toString())));
    }

    private void openPresets() {
        minecraft.setScreen(new BiomePresetScreen(this, disabled, this::reloadRows));
    }

    // The rows read their toggle state from disabled when created, so rebuild them after a preset changed it
    private void reloadRows() {
        int index = Arrays.asList(tabs).indexOf(tabManager.getCurrentTab());
        selectedTab = Math.max(index, 0);
        lastShownId = tabManager.getCurrentTab() instanceof BiomeTab tab && tab.list.lastShown != null ? tab.list.lastShown.id : null;
        rebuildWidgets();
    }

    private void setSortMode(SortMode mode) {
        sortMode = mode;
        for (Tab tab : tabs) {
            if (tab instanceof BiomeTab biomeTab) {
                biomeTab.sortButton.setValue(mode);
                biomeTab.list.sort(mode);
            }
        }
    }

    private void save() {
        Config.DISABLED_BIOMES.set(disabled.stream().sorted().toList());
        Config.SPEC.save();
        onClose();
    }

    // Minecraft first, then other mods alphabetically
    private static int namespaceOrder(String namespace) {
        return namespace.equals(ResourceLocation.DEFAULT_NAMESPACE) ? 0 : 1;
    }

    private static Component tabTitle(String namespace) {
        return ModList.get().getModContainerById(namespace)
                .<Component>map(container -> Component.literal(container.getModInfo().getDisplayName()))
                .orElseGet(() -> Component.translatable("biomepicknchoose.configuration.tab.fallback", namespace));
    }

    private final class BiomeTab implements Tab {
        private final Component title;
        private final BiomeList list;
        private final Button enableAll;
        private final Button disableAll;
        private final CycleButton<SortMode> sortButton;

        BiomeTab(Component title, List<ResourceLocation> biomes) {
            this.title = title;
            this.list = new BiomeList(minecraft, biomes);
            this.enableAll = Button.builder(Component.translatable("biomepicknchoose.configuration.enable_all"), button -> list.setAll(true)).width(150).build();
            this.disableAll = Button.builder(Component.translatable("biomepicknchoose.configuration.disable_all"), button -> list.setAll(false)).width(150).build();
            this.sortButton = CycleButton.<SortMode>builder(mode -> Component.translatable(mode.key))
                    .withValues(SortMode.values())
                    .withInitialValue(sortMode)
                    .create(0, 0, 100, 20, Component.translatable("biomepicknchoose.configuration.sort"), (button, mode) -> setSortMode(mode));
        }

        @Override
        public Component getTabTitle() {
            return title;
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
            consumer.accept(enableAll);
            consumer.accept(disableAll);
            consumer.accept(sortButton);
            consumer.accept(list);
        }

        @Override
        public void doLayout(ScreenRectangle area) {
            // With the side panel, the list and its buttons take the left half
            int listWidth = preview != null ? area.width() / 2 : area.width();
            int buttonWidth = Math.min(100, (listWidth - 40) / 3);
            int left = area.left() + (listWidth - 3 * buttonWidth - 10) / 2;
            enableAll.setWidth(buttonWidth);
            disableAll.setWidth(buttonWidth);
            sortButton.setWidth(buttonWidth);
            enableAll.setPosition(left, area.top() + 4);
            disableAll.setPosition(left + buttonWidth + 5, area.top() + 4);
            sortButton.setPosition(left + 2 * (buttonWidth + 5), area.top() + 4);
            list.rowWidth = preview != null ? NARROW_ROW_WIDTH : ROW_WIDTH;
            list.updateSizeAndPosition(listWidth, area.height() - TAB_HEADER_HEIGHT, area.top() + TAB_HEADER_HEIGHT);
            list.setX(area.left());
        }
    }

    private final class BiomeList extends ContainerObjectSelectionList<BiomeRow> {
        private int rowWidth = ROW_WIDTH;
        // Last hovered or focused row, shown in the side panel when the mouse is elsewhere
        @Nullable
        private BiomeRow lastShown;

        BiomeList(Minecraft minecraft, List<ResourceLocation> biomes) {
            super(minecraft, 0, 0, 0, 24);
            biomes.stream().map(BiomeRow::new).forEach(this::addEntry);
            sort(sortMode);
        }

        // Only on picking a mode, so a row doesn't move away right after toggling it
        void sort(SortMode mode) {
            List<BiomeRow> rows = new ArrayList<>(children());
            rows.sort(mode.comparator);
            replaceEntries(rows);
        }

        void setAll(boolean enabled) {
            children().forEach(row -> row.setEnabled(enabled));
        }

        void show(ResourceLocation id) {
            children().stream().filter(row -> row.id.equals(id)).findFirst().ifPresent(row -> lastShown = row);
        }

        @Nullable
        BiomeRow previewBiome() {
            BiomeRow hovered = getHovered();
            if (hovered != null) lastShown = hovered;
            if (lastShown != null) return lastShown;
            return children().isEmpty() ? null : getFirstElement();
        }

        @Override
        public void setFocused(@Nullable GuiEventListener focused) {
            super.setFocused(focused);
            if (getFocused() != null) lastShown = getFocused();
        }

        @Override
        public int getRowWidth() {
            return rowWidth;
        }
    }

    private final class BiomeRow extends ContainerObjectSelectionList.Entry<BiomeRow> {
        private final ResourceLocation id;
        private final Component name;
        private final CycleButton<Boolean> toggle;

        BiomeRow(ResourceLocation id) {
            this.id = id;
            this.name = Component.translatableWithFallback("biome." + id.getNamespace() + "." + id.getPath(), id.toString());
            this.toggle = CycleButton.booleanBuilder(ON, OFF)
                    .withInitialValue(!disabled.contains(id.toString()))
                    .displayOnlyValue()
                    .withTooltip(enabled -> Tooltip.create(Component.literal(id.toString())))
                    .create(0, 0, 60, 20, name, (button, enabled) -> updateDisabled(enabled));
        }

        void setEnabled(boolean enabled) {
            toggle.setValue(enabled);
            updateDisabled(enabled);
        }

        private void updateDisabled(boolean enabled) {
            if (enabled) disabled.remove(id.toString());
            else disabled.add(id.toString());
        }

        @Override
        public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            int maxNameWidth = width - toggle.getWidth() - 8;
            guiGraphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(name, maxNameWidth)),
                    left, top + (height - font.lineHeight) / 2, 0xFFFFFF);
            toggle.setPosition(left + width - toggle.getWidth(), top + (height - toggle.getHeight()) / 2);
            toggle.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(toggle);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(toggle);
        }
    }

    private enum SortMode {
        ALPHABETICAL("alphabetical", Comparator.comparing(BiomeToggleScreen::sortName)),
        ENABLED_FIRST("enabled_first", Comparator.<BiomeRow, Boolean>comparing(row -> !row.toggle.getValue()).thenComparing(BiomeToggleScreen::sortName)),
        DISABLED_FIRST("disabled_first", Comparator.<BiomeRow, Boolean>comparing(row -> row.toggle.getValue()).thenComparing(BiomeToggleScreen::sortName));

        private final String key;
        private final Comparator<BiomeRow> comparator;

        SortMode(String name, Comparator<BiomeRow> comparator) {
            this.key = "biomepicknchoose.configuration.sort." + name;
            this.comparator = comparator;
        }
    }

    private static String sortName(BiomeRow row) {
        return row.name.getString();
    }
}
