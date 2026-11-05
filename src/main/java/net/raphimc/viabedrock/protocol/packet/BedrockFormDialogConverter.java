/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2026 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viabedrock.protocol.packet;

import com.viaversion.nbt.tag.CompoundTag;
import com.viaversion.viaversion.api.minecraft.Holder;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.libs.mcstructs.converter.impl.v1_21_5.NbtConverter_v1_21_5;
import com.viaversion.viaversion.libs.mcstructs.core.Identifier;
import com.viaversion.viaversion.libs.mcstructs.dialog.ActionButton;
import com.viaversion.viaversion.libs.mcstructs.dialog.AfterAction;
import com.viaversion.viaversion.libs.mcstructs.dialog.Dialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.Input;
import com.viaversion.viaversion.libs.mcstructs.dialog.action.CustomAllAction;
import com.viaversion.viaversion.libs.mcstructs.dialog.body.PlainMessageBody;
import com.viaversion.viaversion.libs.mcstructs.dialog.impl.MultiActionDialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.impl.NoticeDialog;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.BooleanInput;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.NumberRangeInput;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.SingleOptionInput;
import com.viaversion.viaversion.libs.mcstructs.dialog.input.TextInput;
import com.viaversion.viaversion.libs.mcstructs.dialog.serializer.DialogSerializer;
import com.viaversion.viaversion.libs.mcstructs.text.components.StringComponent;
import com.viaversion.viaversion.libs.mcstructs.text.components.TranslationComponent;
import net.lenni0451.mcstructs_bedrock.forms.Form;
import net.lenni0451.mcstructs_bedrock.forms.elements.ButtonFormElement;
import net.lenni0451.mcstructs_bedrock.forms.elements.CheckboxFormElement;
import net.lenni0451.mcstructs_bedrock.forms.elements.DividerFormElement;
import net.lenni0451.mcstructs_bedrock.forms.elements.DropdownFormElement;
import net.lenni0451.mcstructs_bedrock.forms.elements.FormElement;
import net.lenni0451.mcstructs_bedrock.forms.elements.HeaderFormElement;
import net.lenni0451.mcstructs_bedrock.forms.elements.LabelFormElement;
import net.lenni0451.mcstructs_bedrock.forms.elements.SliderFormElement;
import net.lenni0451.mcstructs_bedrock.forms.elements.StepSliderFormElement;
import net.lenni0451.mcstructs_bedrock.forms.elements.TextFieldFormElement;
import net.lenni0451.mcstructs_bedrock.forms.types.ActionForm;
import net.lenni0451.mcstructs_bedrock.forms.types.CustomForm;
import net.lenni0451.mcstructs_bedrock.forms.types.ModalForm;
import net.lenni0451.mcstructs_bedrock.text.utils.BedrockTextUtils;
import net.raphimc.viabedrock.api.util.TextUtil;

import java.util.ArrayList;

final class BedrockFormDialogConverter {

    private static final int BUTTON_WIDTH = 200;
    private static final int FAKE_BUTTON_WIDTH = 300;
    private static final String FAKE_BUTTON_TEXT = "This is not actually a button, but has to be one because dialogs don't support adding text only elements. Clicking it has the same effect as closing the dialog.";

    private BedrockFormDialogConverter() {
    }

    static Dialog convert(final int formId, final Form form, final ProtocolVersion protocolVersion) {
        final Identifier responseIdentifier = Identifier.of("viabedrock", "form/" + formId);
        final CompoundTag exitButtonAdditions = new CompoundTag();
        exitButtonAdditions.putBoolean("exit", true);
        final ActionButton exitButton = new ActionButton(new TranslationComponent("gui.cancel"), BUTTON_WIDTH, new CustomAllAction(responseIdentifier, exitButtonAdditions));

        if (form instanceof ModalForm modalForm) {
            final MultiActionDialog dialog = new MultiActionDialog(TextUtil.stringToTextComponent(TextUtil.toSingleLine(form.getTitle())), true, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), exitButton, 1);
            addText(dialog, modalForm.getText(), protocolVersion);
            final CompoundTag button1Additions = new CompoundTag();
            button1Additions.putInt("button_id", 0);
            dialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(TextUtil.toSingleLine(modalForm.getButton1())), BUTTON_WIDTH, new CustomAllAction(responseIdentifier, button1Additions)));
            final CompoundTag button2Additions = new CompoundTag();
            button2Additions.putInt("button_id", 1);
            dialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(TextUtil.toSingleLine(modalForm.getButton2())), BUTTON_WIDTH, new CustomAllAction(responseIdentifier, button2Additions)));
            return dialog;
        } else if (form instanceof ActionForm actionForm) {
            return convertActionForm(actionForm, responseIdentifier, exitButton, protocolVersion);
        } else if (form instanceof CustomForm customForm) {
            return convertCustomForm(customForm, responseIdentifier, exitButtonAdditions, protocolVersion);
        } else {
            throw new IllegalArgumentException("Unhandled form type: " + form.getClass().getSimpleName());
        }
    }

    static Holder<CompoundTag> serialize(final Dialog dialog) {
        return Holder.of((CompoundTag) DialogSerializer.V1_21_6.getDirectCodec().serialize(NbtConverter_v1_21_5.INSTANCE, dialog).get());
    }

    private static Dialog convertActionForm(final ActionForm form, final Identifier responseIdentifier, final ActionButton exitButton, final ProtocolVersion protocolVersion) {
        boolean hasAction = false;
        for (FormElement element : form.getElements()) {
            if (!(element instanceof DividerFormElement)) {
                hasAction = true;
                break;
            }
        }
        if (!hasAction) {
            final NoticeDialog dialog = new NoticeDialog(TextUtil.stringToTextComponent(TextUtil.toSingleLine(form.getTitle())), true, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), exitButton);
            addText(dialog, form.getText(), protocolVersion);
            return dialog;
        }

        final MultiActionDialog dialog = new MultiActionDialog(TextUtil.stringToTextComponent(TextUtil.toSingleLine(form.getTitle())), true, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), exitButton, 1);
        addText(dialog, form.getText(), protocolVersion);
        int buttonIndex = 0;
        for (FormElement element : form.getElements()) {
            if (element instanceof ButtonFormElement button) {
                final CompoundTag additions = new CompoundTag();
                additions.putInt("button_id", buttonIndex++);
                dialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(TextUtil.toSingleLine(button.getText())), BUTTON_WIDTH, new CustomAllAction(responseIdentifier, additions)));
            } else if (element instanceof HeaderFormElement header) {
                dialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(TextUtil.toSingleLine(header.getText())), new StringComponent(FAKE_BUTTON_TEXT), FAKE_BUTTON_WIDTH, exitButton.getAction()));
            } else if (element instanceof LabelFormElement label) {
                dialog.getActions().add(new ActionButton(TextUtil.stringToTextComponent(TextUtil.toSingleLine(label.getText())), new StringComponent(FAKE_BUTTON_TEXT), FAKE_BUTTON_WIDTH, exitButton.getAction()));
            } else if (!(element instanceof DividerFormElement)) {
                throw new IllegalArgumentException("Unhandled form element type: " + element.getClass().getSimpleName());
            }
        }
        return dialog;
    }

    private static Dialog convertCustomForm(final CustomForm form, final Identifier responseIdentifier, final CompoundTag exitButtonAdditions, final ProtocolVersion protocolVersion) {
        final MultiActionDialog dialog = new MultiActionDialog(TextUtil.stringToTextComponent(TextUtil.toSingleLine(form.getTitle())), false, false, AfterAction.CLOSE, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 1);
        for (int elementIndex = 0; elementIndex < form.getElements().length; elementIndex++) {
            final FormElement element = form.getElements()[elementIndex];
            final String inputKey = String.valueOf(elementIndex);
            if (element instanceof CheckboxFormElement checkbox) {
                final BooleanInput input = new BooleanInput(TextUtil.stringToTextComponent(TextUtil.toSingleLine(checkbox.getText())));
                input.setInitial(checkbox.getDefaultValue());
                dialog.getInputs().add(new Input(inputKey, input));
            } else if (element instanceof DropdownFormElement dropdown) {
                dialog.getInputs().add(new Input(inputKey, singleOptionInput(dropdown.getText(), dropdown.getOptions(), dropdown.getDefaultOption())));
            } else if (element instanceof SliderFormElement slider) {
                final NumberRangeInput input = new NumberRangeInput(TextUtil.stringToTextComponent(TextUtil.toSingleLine(slider.getText())), new NumberRangeInput.Range(slider.getMin(), slider.getMax(), slider.getDefaultValue(), slider.getStep()));
                dialog.getInputs().add(new Input(inputKey, input));
            } else if (element instanceof StepSliderFormElement stepSlider) {
                dialog.getInputs().add(new Input(inputKey, singleOptionInput(stepSlider.getText(), stepSlider.getSteps(), stepSlider.getDefaultStep())));
            } else if (element instanceof TextFieldFormElement textField) {
                final TextInput input = new TextInput(TextUtil.stringToTextComponent(TextUtil.toSingleLine(textField.getText())));
                input.setMaxLength(100);
                input.setInitial(textField.getDefaultValue());
                dialog.getInputs().add(new Input(inputKey, input));
            } else if (element instanceof HeaderFormElement header) {
                addText(dialog, header.getText(), protocolVersion);
            } else if (element instanceof LabelFormElement label) {
                addText(dialog, label.getText(), protocolVersion);
            } else if (element instanceof DividerFormElement) {
                if (protocolVersion.newerThanOrEqualTo(ProtocolVersion.v1_21_6)) {
                    final TextInput input = new TextInput(new StringComponent());
                    input.setLabelVisible(false);
                    input.setMaxLength(Integer.MAX_VALUE);
                    input.setMultiline(new TextInput.MultilineOptions(null, 1));
                    dialog.getInputs().add(new Input("dummy", input));
                }
            } else {
                throw new IllegalArgumentException("Unhandled form element type: " + element.getClass().getSimpleName());
            }
        }
        dialog.getActions().add(new ActionButton(new TranslationComponent("gui.done"), BUTTON_WIDTH, new CustomAllAction(responseIdentifier, null)));
        dialog.getActions().add(new ActionButton(new TranslationComponent("gui.cancel"), BUTTON_WIDTH, new CustomAllAction(responseIdentifier, exitButtonAdditions)));
        return dialog;
    }

    private static SingleOptionInput singleOptionInput(final String label, final String[] options, final int defaultOption) {
        final SingleOptionInput input = new SingleOptionInput(new ArrayList<>(Math.max(1, options.length)), TextUtil.stringToTextComponent(TextUtil.toSingleLine(label)));
        if (options.length == 0) {
            // 基岩把空选择器响应用索引 0 表示；在用一个视觉上为空的选项满足 Java 非空对话框编解码要求的同时，
            // 保留该响应约定。
            input.getOptions().add(new SingleOptionInput.Entry("0", new StringComponent(), true));
            return input;
        }
        for (int optionIndex = 0; optionIndex < options.length; optionIndex++) {
            input.getOptions().add(new SingleOptionInput.Entry(String.valueOf(optionIndex), TextUtil.stringToTextComponent(TextUtil.toSingleLine(options[optionIndex])), optionIndex == defaultOption));
        }
        return input;
    }

    private static void addText(final Dialog dialog, final String text, final ProtocolVersion protocolVersion) {
        if (dialog.getInputs().isEmpty()) {
            for (String line : BedrockTextUtils.split(text, "\n")) {
                dialog.getBody().add(new PlainMessageBody(TextUtil.stringToTextComponent(line)));
            }
        } else if (protocolVersion.newerThanOrEqualTo(ProtocolVersion.v1_21_6)) {
            for (String line : BedrockTextUtils.split(text, "\n")) {
                final TextInput input = new TextInput(TextUtil.stringToTextComponent(line));
                input.setMaxLength(Integer.MAX_VALUE);
                input.setMultiline(new TextInput.MultilineOptions(null, 1));
                dialog.getInputs().add(new Input("dummy", input));
            }
        } else {
            dialog.getInputs().add(new Input("dummy", new BooleanInput(TextUtil.stringToTextComponent(TextUtil.toSingleLine(text)))));
        }
    }

}
