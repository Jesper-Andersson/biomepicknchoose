package com.github.Jesper_Andersson.biomepicknchoose.client.gui;

import com.github.Jesper_Andersson.biomepicknchoose.client.preview.BiomePreviews;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeCatalog;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeConfig;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeDimension;
import com.github.Jesper_Andersson.biomepicknchoose.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * On/off toggles for every known overworld biome, with one tab per mod. Done saves them to this game's config, or to
 * the server's when an operator opened the screen with {@code /biomepicknchoose config}.
 */
public final class BiomeToggleScreen extends Screen {
    private static final int FOOTER_HEIGHT = 56;
    private static final int TAB_HEADER_HEIGHT = 28;
    // The dimension switch and search box, between the tab bar and the tabs
    private static final int SEARCH_ROW_HEIGHT = 24;
    private static final int DIMENSION_BUTTON_WIDTH = 110;
    private static final int ROW_WIDTH = 310;
    private static final int NARROW_ROW_WIDTH = 250;
    // Space between widgets side by side, and room kept beside the list for its scroll bar
    private static final int WIDGET_GAP = 5;
    private static final int SCROLL_BAR_ROOM = 24;
    // Low enough for automatic GUI scale, which makes the screen 426 to 480 wide on common monitors
    private static final int PANEL_MIN_SCREEN_WIDTH = 400;
    // Rows narrower than this get a smaller thumbnail and toggle, to leave room for the name
    private static final int COMPACT_ROW_WIDTH = 240;
    private static final int ROW_HEIGHT = 40;
    // Room left and right of a tab title, the narrowest tab, and the space kept free beside the tab bar
    private static final int TAB_PADDING = 16;
    private static final int TAB_MIN_WIDTH = 60;
    private static final int TAB_MARGIN = 28;
    private static final int PANEL_TEXT_HEIGHT = 26;
    private static final int PANEL_BUTTON_HEIGHT = 24;
    // Text colours need an alpha channel, or the text is invisible
    private static final int HINT_COLOR = 0xFFA0A0A0;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    // Cave biome names, so they stand out from surface biomes
    private static final int CAVE_TEXT_COLOR = 0xFFAAAAAA;
    // Ocean and river biome names
    private static final int WATER_TEXT_COLOR = 0xFF6FA8FF;
    // Names of biomes the last loaded world never places
    private static final int UNUSED_TEXT_COLOR = 0xFF707070;
    // Thumbnails in the rows, half the size of the pictures BiomePreviews makes, so they are sharp at GUI scale 2
    private static final int THUMBNAIL_WIDTH = BiomePreviews.THUMBNAIL_WIDTH / 2;
    private static final int THUMBNAIL_HEIGHT = BiomePreviews.THUMBNAIL_HEIGHT / 2;
    private static final int COMPACT_THUMBNAIL_WIDTH = 48;
    private static final int COMPACT_THUMBNAIL_HEIGHT = 27;
    private static final Component ON = CommonComponents.OPTION_ON.copy().withStyle(ChatFormatting.GREEN);
    private static final Component OFF = CommonComponents.OPTION_OFF.copy().withStyle(ChatFormatting.RED);

    private final Screen parent;
    private final Set<String> disabled;
    private final BiomeCatalog catalog;
    private final Consumer<List<String>> onSave;
    // Editing the config of the server this client is connected to
    private final boolean remote;
    private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    private TabNavigationBar tabNavigationBar;
    private CycleButton<BiomeDimension> dimensionButton;
    private EditBox searchBox;
    // Kept when the widgets are rebuilt, like on switching dimension
    private BiomeDimension dimension = BiomeDimension.OVERWORLD;
    private String query = "";
    private Tab[] tabs = new Tab[0];
    private Button presetsButton;
    private Button doneButton;
    private Button cancelButton;
    private Button removePreview;
    // Biome the remove button applies to, set while it is shown
    @Nullable
    private Identifier removeTarget;
    // Restored when the widgets are rebuilt after loading a preset
    private int selectedTab;
    // Shared by all tabs
    private SortMode sortMode = SortMode.ALPHABETICAL;
    @Nullable
    private Identifier lastShownId;
    private boolean hasKnownBiomes;
    // Preview image area, or null when the screen is too narrow for the side panel
    @Nullable
    private ScreenRectangle preview;

    /** Edits this game's config. */
    public BiomeToggleScreen(Screen parent) {
        this(parent, Component.translatable("biomepicknchoose.configuration.title"), BiomeCatalog.local(),
                BiomeConfig.disabledBiomes(), null, false);
    }

    /** Edits the server's config, from the biomes it sent. onSave sends the disabled biomes back. */
    public static BiomeToggleScreen forServer(@Nullable Screen parent, BiomeCatalog catalog, List<String> disabled,
                                              Consumer<List<String>> onSave) {
        return new BiomeToggleScreen(parent, Component.translatable("biomepicknchoose.configuration.title.server"), catalog,
                disabled, onSave, true);
    }

    private BiomeToggleScreen(@Nullable Screen parent, Component title, BiomeCatalog catalog, List<String> disabled,
                              @Nullable Consumer<List<String>> onSave, boolean remote) {
        super(title);
        this.parent = parent;
        this.catalog = catalog;
        this.disabled = new HashSet<>(disabled);
        this.onSave = onSave != null ? onSave : biomes -> BiomeConfig.save(catalog.allKnown(), biomes);
        this.remote = remote;
    }

    @Override
    protected void init() {
        // Until a world was loaded in this game, the menu may miss biomes of other mods. The server's list is complete
        hasKnownBiomes = remote || !catalog.generating().isEmpty();
        tabs = createTabs();
        tabNavigationBar = TabNavigationBar.builder(tabManager, width).addTabs(tabs).build();
        addRenderableWidget(tabNavigationBar);
        dimensionButton = addRenderableWidget(createDimensionButton());
        searchBox = addRenderableWidget(createSearchBox());
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

    // One tab per mod, with the biomes it adds to the shown dimension
    private Tab[] createTabs() {
        Map<String, List<Identifier>> byNamespace = new TreeMap<>(
                Comparator.comparingInt(BiomeToggleScreen::namespaceOrder).thenComparing(Comparator.naturalOrder()));
        for (Identifier id : catalog.known().getOrDefault(dimension, Set.of())) {
            byNamespace.computeIfAbsent(id.getNamespace(), namespace -> new ArrayList<>()).add(id);
        }
        return byNamespace.entrySet().stream()
                .map(entry -> new BiomeTab(tabTitle(entry.getKey()), entry.getValue()))
                .toArray(Tab[]::new);
    }

    private CycleButton<BiomeDimension> createDimensionButton() {
        // Dimensions without any biome to list, like a mod dimension the scan found no biomes for, are left out
        List<BiomeDimension> dimensions = catalog.known().entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
        if (!dimensions.contains(dimension)) dimension = BiomeDimension.OVERWORLD;
        Component label = Component.translatable("biomepicknchoose.configuration.dimension");
        return CycleButton.<BiomeDimension>builder(BiomeDimension::displayName, dimension)
                .withValues(dimensions)
                .displayOnlyValue()
                .withTooltip(value -> Tooltip.create(label))
                .create(0, 0, DIMENSION_BUTTON_WIDTH, 20, label, (button, value) -> switchDimension(value));
    }

    private EditBox createSearchBox() {
        Component hint = Component.translatable("biomepicknchoose.configuration.search");
        EditBox box = new EditBox(font, 0, 0, 200, 20, hint);
        box.setHint(hint.copy().withStyle(ChatFormatting.DARK_GRAY));
        box.setValue(query);
        box.setResponder(this::search);
        return box;
    }

    @Override
    protected void repositionElements() {
        if (tabNavigationBar == null) return;
        tabNavigationBar.setWidth(width);
        tabNavigationBar.arrangeElements();
        arrangeTabs();
        int top = tabNavigationBar.getRectangle().bottom();
        int areaHeight = height - FOOTER_HEIGHT - top;
        preview = null;
        if (width >= PANEL_MIN_SCREEN_WIDTH) {
            int half = width / 2;
            int previewWidth = Math.min(half - 30, (areaHeight - 8 - PANEL_TEXT_HEIGHT - PANEL_BUTTON_HEIGHT) * 16 / 9);
            if (previewWidth > 0) preview = new ScreenRectangle(half + 10, top + 4, previewWidth, previewWidth * 9 / 16);
        }
        // Over the list, which takes the left half with the side panel
        int listWidth = preview != null ? width / 2 : width;
        int rowWidth = rowWidth(listWidth);
        int rowLeft = (listWidth - rowWidth) / 2;
        dimensionButton.setPosition(rowLeft, top + 4);
        searchBox.setWidth(rowWidth - DIMENSION_BUTTON_WIDTH - WIDGET_GAP);
        searchBox.setPosition(rowLeft + DIMENSION_BUTTON_WIDTH + WIDGET_GAP, top + 4);
        tabManager.setTabArea(new ScreenRectangle(0, top + SEARCH_ROW_HEIGHT, width, areaHeight - SEARCH_ROW_HEIGHT));
        presetsButton.setPosition(width / 2 - 155, height - 28);
        doneButton.setPosition(width / 2 - 50, height - 28);
        cancelButton.setPosition(width / 2 + 55, height - 28);
    }

    // The width of the list rows, and of the search row above them
    private int rowWidth(int listWidth) {
        return Math.min(preview != null ? NARROW_ROW_WIDTH : ROW_WIDTH, listWidth - SCROLL_BAR_ROOM);
    }

    // Vanilla gives every tab the same width within 400 pixels, which cuts long mod names off early. Instead, each tab
    // fits its title. When they don't all fit, the widest ones shrink to the same width and the rest keep theirs
    private void arrangeTabs() {
        List<TabButton> buttons = tabNavigationBar.children().stream()
                .filter(TabButton.class::isInstance).map(TabButton.class::cast).toList();
        if (buttons.isEmpty()) return;
        int[] widths = buttons.stream()
                .mapToInt(button -> Math.max(TAB_MIN_WIDTH, font.width(button.getMessage()) + TAB_PADDING))
                .toArray();
        int cap = tabWidthCap(widths, width - TAB_MARGIN);
        int total = 0;
        for (int i = 0; i < widths.length; i++) {
            // Even widths, like vanilla
            widths[i] = Math.min(widths[i], cap) & ~1;
            total += widths[i];
        }
        int x = (width - total) / 2 & ~1;
        for (int i = 0; i < buttons.size(); i++) {
            buttons.get(i).setWidth(widths[i]);
            buttons.get(i).setX(x);
            x += widths[i];
        }
    }

    // The largest width that every tab can be limited to so they fit in available, or no limit if they already fit
    private static int tabWidthCap(int[] widths, int available) {
        int[] sorted = widths.clone();
        Arrays.sort(sorted);
        int remaining = available;
        for (int i = 0; i < sorted.length; i++) {
            int left = sorted.length - i;
            if (sorted[i] * left > remaining) return Math.max(remaining / left, 2);
            remaining -= sorted[i];
        }
        return Integer.MAX_VALUE;
    }

    private void switchDimension(BiomeDimension value) {
        dimension = value;
        selectedTab = 0;
        lastShownId = null;
        rebuildWidgets();
    }

    private void search(String text) {
        query = text;
        for (Tab tab : tabs) {
            if (tab instanceof BiomeTab biomeTab) biomeTab.list.filter(query);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return tabNavigationBar.keyPressed(event) || super.keyPressed(event);
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
        if (tabManager.getCurrentTab() instanceof BiomeTab tab && tab.list.children().isEmpty()) {
            guiGraphics.drawCenteredString(font, Component.translatable("biomepicknchoose.configuration.search.none"),
                    tab.list.getX() + tab.list.getWidth() / 2, tab.list.getY() + 12, HINT_COLOR);
        }
        int y = height - FOOTER_HEIGHT + 6;
        guiGraphics.drawCenteredString(font, Component.translatable(remote ? "biomepicknchoose.configuration.hint.apply.server" : "biomepicknchoose.configuration.hint.apply"), width / 2, y, HINT_COLOR);
        if (!hasKnownBiomes) {
            guiGraphics.drawCenteredString(font, Component.translatable("biomepicknchoose.configuration.hint.load_world"), width / 2, y + 11, HINT_COLOR);
        }
    }

    private void renderPreview(GuiGraphics guiGraphics, BiomeRow row) {
        int x = preview.left();
        int y = preview.top();
        int w = preview.width();
        int h = preview.height();
        guiGraphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, HINT_COLOR);
        Identifier texture = BiomePreviews.textureFor(row.id);
        if (texture != null) {
            guiGraphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, w, h, w, h);
        } else {
            guiGraphics.fill(x, y, x + w, y + h, 0xFF101010);
            List<FormattedCharSequence> lines = font.split(Component.translatable("biomepicknchoose.configuration.preview.none"), w - 16);
            int lineY = y + (h - lines.size() * font.lineHeight) / 2;
            for (FormattedCharSequence line : lines) {
                guiGraphics.drawCenteredString(font, line, x + w / 2, lineY, HINT_COLOR);
                lineY += font.lineHeight;
            }
        }
        guiGraphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(row.displayName(), w)), x, y + h + 5, row.textColor());
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
        Identifier id = removeTarget;
        if (id == null) return;
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) BiomePreviews.delete(id);
            minecraft.setScreen(this);
        }, title, Component.translatable("biomepicknchoose.configuration.preview.remove.confirm", id.toString())));
    }

    private void openPresets() {
        minecraft.setScreen(new BiomePresetScreen(this, catalog.allKnown(), disabled, this::reloadRows));
    }

    // The rows read their toggle state from disabled when created, so rebuild them after a preset changed it
    private void reloadRows() {
        int index = Arrays.asList(tabs).indexOf(tabManager.getCurrentTab());
        selectedTab = Math.max(index, 0);
        lastShownId = tabManager.getCurrentTab() instanceof BiomeTab tab && tab.list.getSelected() != null ? tab.list.getSelected().id : null;
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
        onSave.accept(disabled.stream().sorted().toList());
        onClose();
    }

    // Minecraft first, then other mods alphabetically
    private static int namespaceOrder(String namespace) {
        return namespace.equals(Identifier.DEFAULT_NAMESPACE) ? 0 : 1;
    }

    private static Component tabTitle(String namespace) {
        return Services.PLATFORM.getModName(namespace)
                .<Component>map(Component::literal)
                .orElseGet(() -> Component.translatable("biomepicknchoose.configuration.tab.fallback", namespace));
    }

    private final class BiomeTab implements Tab {
        private final Component title;
        private final BiomeList list;
        private final Button enableAll;
        private final Button disableAll;
        private final CycleButton<SortMode> sortButton;

        BiomeTab(Component title, List<Identifier> biomes) {
            this.title = title;
            this.list = new BiomeList(minecraft, biomes);
            Tooltip shownOnly = Tooltip.create(Component.translatable("biomepicknchoose.configuration.all.shown"));
            this.enableAll = Button.builder(Component.translatable("biomepicknchoose.configuration.enable_all"), button -> list.setAll(true))
                    .width(150).tooltip(shownOnly).build();
            this.disableAll = Button.builder(Component.translatable("biomepicknchoose.configuration.disable_all"), button -> list.setAll(false))
                    .width(150).tooltip(shownOnly).build();
            this.sortButton = CycleButton.<SortMode>builder(mode -> Component.translatable(mode.key), sortMode)
                    .withValues(SortMode.values())
                    .create(0, 0, 100, 20, Component.translatable("biomepicknchoose.configuration.sort"), (button, mode) -> setSortMode(mode));
        }

        @Override
        public Component getTabTitle() {
            return title;
        }

        @Override
        public Component getTabExtraNarration() {
            return CommonComponents.EMPTY;
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
            int left = area.left() + (listWidth - 3 * buttonWidth - 2 * WIDGET_GAP) / 2;
            enableAll.setWidth(buttonWidth);
            disableAll.setWidth(buttonWidth);
            sortButton.setWidth(buttonWidth);
            enableAll.setPosition(left, area.top() + 4);
            disableAll.setPosition(left + buttonWidth + WIDGET_GAP, area.top() + 4);
            sortButton.setPosition(left + 2 * (buttonWidth + WIDGET_GAP), area.top() + 4);
            list.rowWidth = rowWidth(listWidth);
            list.updateSizeAndPosition(listWidth, area.height() - TAB_HEADER_HEIGHT, area.top() + TAB_HEADER_HEIGHT);
            list.setX(area.left());
        }
    }

    // The selected row is shown in the side panel. Picked by clicking a row or with the keyboard, not by hovering, so
    // moving the mouse to the panel doesn't change it
    private final class BiomeList extends ContainerObjectSelectionList<BiomeRow> {
        private int rowWidth = ROW_WIDTH;
        // Every row of the tab, also the ones the search hides
        private final List<BiomeRow> allRows;

        BiomeList(Minecraft minecraft, List<Identifier> biomes) {
            super(minecraft, 0, 0, 0, ROW_HEIGHT);
            allRows = biomes.stream().map(BiomeRow::new).toList();
            filter(query);
        }

        // Shows the rows whose name or id contains the text, in the current sort order
        void filter(String text) {
            String needle = text.trim().toLowerCase(Locale.ROOT);
            BiomeRow selected = getSelected();
            List<BiomeRow> rows = new ArrayList<>(allRows.stream().filter(row -> row.matches(needle)).toList());
            rows.sort(sortMode.comparator);
            replaceEntries(rows);
            setSelected(rows.contains(selected) ? selected : null);
            // The list keeps its scroll position, which may be past the end of a shorter list
            setScrollAmount(scrollAmount());
        }

        // Only on picking a mode, so a row doesn't move away right after toggling it
        void sort(SortMode mode) {
            BiomeRow selected = getSelected();
            List<BiomeRow> rows = new ArrayList<>(children());
            rows.sort(mode.comparator);
            replaceEntries(rows);
            setSelected(selected);
        }

        void setAll(boolean enabled) {
            children().forEach(row -> row.setEnabled(enabled));
        }

        void show(Identifier id) {
            children().stream().filter(row -> row.id.equals(id)).findFirst().ifPresent(this::setSelected);
        }

        @Nullable
        BiomeRow previewBiome() {
            BiomeRow selected = getSelected();
            if (selected != null) return selected;
            return children().isEmpty() ? null : children().getFirst();
        }

        // Focusing a row selects it. Keep the selection when the focus moves elsewhere, like to Enable all
        @Override
        public void setFocused(@Nullable GuiEventListener focused) {
            BiomeRow selected = getSelected();
            super.setFocused(focused);
            if (focused == null) setSelected(selected);
        }

        // ContainerObjectSelectionList never highlights a row
        @Override
        protected boolean entriesCanBeSelected() {
            return true;
        }

        @Override
        public int getRowWidth() {
            return rowWidth;
        }
    }

    private final class BiomeRow extends ContainerObjectSelectionList.Entry<BiomeRow> {
        private final Identifier id;
        private final Component name;
        private final CycleButton<Boolean> toggle;

        BiomeRow(Identifier id) {
            this.id = id;
            this.name = Component.translatableWithFallback("biome." + id.getNamespace() + "." + id.getPath(), id.toString());
            this.toggle = CycleButton.booleanBuilder(ON, OFF, !disabled.contains(id.toString()))
                    .displayOnlyValue()
                    .withTooltip(enabled -> Tooltip.create(tooltip()))
                    .create(0, 0, 60, 20, name, (button, enabled) -> updateDisabled(enabled));
        }

        private Component tooltip() {
            MutableComponent text = Component.literal(id.toString());
            if (isCave()) text.append("\n").append(Component.translatable("biomepicknchoose.configuration.cave").withStyle(ChatFormatting.GRAY));
            if (isWater()) text.append("\n").append(Component.translatable("biomepicknchoose.configuration.water").withStyle(ChatFormatting.BLUE));
            if (isUnused()) text.append("\n").append(Component.translatable("biomepicknchoose.configuration.unused").withStyle(ChatFormatting.YELLOW));
            return text;
        }

        boolean matches(String needle) {
            return needle.isEmpty() || id.toString().contains(needle) || name.getString().toLowerCase(Locale.ROOT).contains(needle);
        }

        private boolean isCave() {
            return catalog.caves().contains(id);
        }

        private boolean isWater() {
            return catalog.water().contains(id);
        }

        private boolean isUnused() {
            return catalog.isUnused(dimension, id);
        }

        Component displayName() {
            return isUnused() ? name.copy().withStyle(ChatFormatting.ITALIC) : name;
        }

        int textColor() {
            if (isUnused()) return UNUSED_TEXT_COLOR;
            if (isCave()) return CAVE_TEXT_COLOR;
            return isWater() ? WATER_TEXT_COLOR : TEXT_COLOR;
        }

        void setEnabled(boolean enabled) {
            toggle.setValue(enabled);
            updateDisabled(enabled);
        }

        private void updateDisabled(boolean enabled) {
            if (enabled) disabled.remove(id.toString());
            else disabled.add(id.toString());
        }

        // Clicking anywhere on the row selects it, not only on the toggle
        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
            super.mouseClicked(event, isDoubleClick);
            return event.button() == 0;
        }

        @Override
        public void renderContent(GuiGraphics guiGraphics, int mouseX, int mouseY, boolean hovering, float partialTick) {
            int left = getContentX();
            int top = getContentY();
            int width = getContentWidth();
            int height = getContentHeight();
            boolean compact = width < COMPACT_ROW_WIDTH;
            int thumbnailWidth = compact ? COMPACT_THUMBNAIL_WIDTH : THUMBNAIL_WIDTH;
            int thumbnailHeight = compact ? COMPACT_THUMBNAIL_HEIGHT : THUMBNAIL_HEIGHT;
            toggle.setWidth(compact ? 44 : 60);
            int thumbnailTop = top + (height - thumbnailHeight) / 2;
            Identifier thumbnail = BiomePreviews.thumbnailFor(id);
            if (thumbnail != null) {
                guiGraphics.blit(RenderPipelines.GUI_TEXTURED, thumbnail, left, thumbnailTop, 0, 0, thumbnailWidth, thumbnailHeight,
                        BiomePreviews.THUMBNAIL_WIDTH, BiomePreviews.THUMBNAIL_HEIGHT, BiomePreviews.THUMBNAIL_WIDTH, BiomePreviews.THUMBNAIL_HEIGHT);
            } else {
                guiGraphics.fill(left, thumbnailTop, left + thumbnailWidth, thumbnailTop + thumbnailHeight, 0xFF101010);
            }
            int nameLeft = left + thumbnailWidth + 6;
            int maxNameWidth = width - thumbnailWidth - 6 - toggle.getWidth() - 6;
            guiGraphics.drawString(font, Language.getInstance().getVisualOrder(font.substrByWidth(displayName(), maxNameWidth)),
                    nameLeft, top + (height - font.lineHeight) / 2, textColor());
            toggle.setPosition(left + width - toggle.getWidth(), top + (height - toggle.getHeight()) / 2);
            toggle.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            // Not List.of: a row selected by clicking beside the toggle has nothing focused inside it, and the arrow key
            // navigation then looks up the index of null, which List.of rejects
            return Collections.singletonList(toggle);
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
