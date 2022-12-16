/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.vibrator.persistence;

import static com.android.internal.vibrator.persistence.XmlConstants.TAG_PREDEFINED_EFFECT;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_PRIMITIVE_EFFECT;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_VIBRATION;
import static com.android.internal.vibrator.persistence.XmlConstants.TAG_WAVEFORM_EFFECT;

import android.annotation.NonNull;
import android.os.VibrationEffect;

import com.android.internal.vibrator.persistence.SerializedVibrationEffect.SerializedSegment;
import com.android.modules.utils.TypedXmlPullParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser implementation for {@link VibrationEffect}.
 *
 * <p>This parser does not support effects created with {@link VibrationEffect.WaveformBuilder} nor
 * {@link VibrationEffect.Composition#addEffect(VibrationEffect)}. It only supports vibration
 * effects defined as:
 *
 * * Predefined vibration effects
 *
 * <pre>VibrationEffect
 *   {@code
 *     <vibration>
 *       <predefined-effect id="0" />
 *     </vibration>
 *   }
 * </pre>
 *
 * * Waveform vibration effects
 *
 * <pre>
 *   {@code
 *     <vibration>
 *       <waveform-effect>
 *         <waveform-entry amplitude="default" durationMs="10" />
 *         <waveform-entry amplitude="0" durationMs="10" />
 *         <waveform-entry amplitude="255" durationMs="100" />
 *         <repeating>
 *           <waveform-entry amplitude="128" durationMs="30" />
 *           <waveform-entry amplitude="192" durationMs="60" />
 *           <waveform-entry amplitude="255" durationMs="20" />
 *         </repeating>
 *       </waveform-effect>
 *     </vibration>
 *   }
 * </pre>
 *
 * * Primitive composition effects
 *
 * <pre>
 *   {@code
 *     <vibration>
 *       <primitive-effect id="1" />
 *       <primitive-effect id="2" scale="0.5" delayMs="100" />
 *     </vibration>
 *   }
 * </pre>
 *
 * @hide
 */
public class VibrationEffectXmlParser {

    /**
     * Parses the current XML tag with all nested tags into a single {@link XmlSerializedVibration}
     * wrapping a {@link VibrationEffect}.
     *
     * @see XmlParser#parseTag(TypedXmlPullParser)
     */
    @NonNull
    public static XmlSerializedVibration<VibrationEffect> parseTag(
            @NonNull TypedXmlPullParser parser) throws XmlParserException, IOException {
        XmlValidator.checkStartTag(parser, TAG_VIBRATION);
        XmlValidator.checkTagHasNoUnexpectedAttributes(parser);
        return parseVibrationContent(parser);
    }

    /**
     * Reads all tags within the currently open tag into a serialized representation of a
     * {@link VibrationEffect}, skipping any validation for the top level tag itself.
     *
     * <p>This can be reused for reading a vibration from an XML root tag or from within a combined
     * vibration, but it should always be called from places that validates the top level tag.
     */
    static SerializedVibrationEffect parseVibrationContent(TypedXmlPullParser parser)
            throws XmlParserException, IOException {
        String vibrationTagName = parser.getName();
        int vibrationTagDepth = parser.getDepth();

        XmlValidator.checkParserCondition(
                XmlReader.readNextTagWithin(parser, vibrationTagDepth),
                "Unsupported empty vibration tag");

        SerializedVibrationEffect serializedVibration;

        switch (parser.getName()) {
            case TAG_PREDEFINED_EFFECT:
                serializedVibration = new SerializedVibrationEffect(
                        SerializedPredefinedEffect.Parser.parseNext(parser));
                break;
            case TAG_PRIMITIVE_EFFECT:
                serializedVibration = new SerializedVibrationEffect(
                        parsePrimitiveList(parser, vibrationTagDepth));
                break;
            case TAG_WAVEFORM_EFFECT:
                serializedVibration = new SerializedVibrationEffect(
                        SerializedAmplitudeStepWaveform.Parser.parseNext(parser));
                break;
            default:
                throw new XmlParserException("Unexpected tag " + parser.getName()
                        + " in vibration tag " + vibrationTagName);
        }

        // Consume tag.
        XmlReader.readEndTag(parser, vibrationTagName, vibrationTagDepth);

        return serializedVibration;
    }

    private static SerializedSegment[] parsePrimitiveList(
            TypedXmlPullParser parser, int outerDepth) throws XmlParserException, IOException {
        List<SerializedSegment> segments = new ArrayList<>();

        do { // First primitive tag already open
            segments.add(SerializedCompositionPrimitive.Parser.parseNext(parser));
        } while (XmlReader.readNextTagWithin(parser, outerDepth));

        return segments.toArray(new SerializedSegment[segments.size()]);
    }
}
