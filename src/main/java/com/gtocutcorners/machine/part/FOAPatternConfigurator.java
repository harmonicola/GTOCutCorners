package com.gtocutcorners.machine.part;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.gui.widget.IntInputWidget;
import com.gregtechceu.gtceu.api.gui.widget.ToggleButtonWidget;
import com.gregtechceu.gtceu.data.lang.LangHandler;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fancy configurator for the Forge Pattern Mode (神锻样板模式), ported from
 * GTLAdditions' FOAPatternConfigurator.
 */
public class FOAPatternConfigurator implements IFancyConfigurator {

    private static final String MODE = "gtocutcorners.machine.me_super_pattern_buffer.foa_mode";
    private static final String TITLE = "gtocutcorners.machine.me_super_pattern_buffer.foa_config.title";
    private static final String TOOLTIPS = "gtocutcorners.machine.me_super_pattern_buffer.foa_config.tooltip";
    private static final String MULTIPLIER = "gtocutcorners.machine.me_super_pattern_buffer.foa_multiplier";

    private final MESuperPatternBufferPartMachine machine;

    public FOAPatternConfigurator(MESuperPatternBufferPartMachine machine) {
        this.machine = machine;
    }

    @Override
    public Component getTitle() {
        return Component.translatable(TITLE);
    }

    @Override
    public IGuiTexture getIcon() {
        return new ItemStackTexture(machine.getDefinition().asStack());
    }

    @Override
    public List<Component> getTooltips() {
        List<Component> tooltips = new ArrayList<>();
        tooltips.add(getTitle());
        tooltips.addAll(LangHandler.getMultiLang(TOOLTIPS));
        return tooltips;
    }

    @Override
    public Widget createConfigurator() {
        WidgetGroup group = new WidgetGroup(0, 0, 118, 56);
        group.addWidget(new ToggleButtonWidget(
            6, 5, 20, 20,
            GuiTextures.BUTTON_POWER,
            machine::isFOAModeEnabled,
            machine::setFOAModeEnabled
        ).setTooltipText(MODE));
        group.addWidget(new LabelWidget(32, 10, MODE));
        group.addWidget(new LabelWidget(6, 36, MULTIPLIER));
        group.addWidget(new IntInputWidget(
            58, 31, 54, 20,
            machine::getFOAPatternOutputMultiplier,
            value -> machine.setFOAPatternOutputMultiplier(
                value == null ? machine.getFOAPatternOutputMultiplier() : value)
        ).setMin(MESuperPatternBufferPartMachine.MIN_MULTIPLIER)
            .setMax(MESuperPatternBufferPartMachine.MAX_MULTIPLIER));
        return group;
    }
}
