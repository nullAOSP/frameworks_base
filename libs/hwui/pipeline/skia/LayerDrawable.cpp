/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "LayerDrawable.h"

#include "GrBackendSurface.h"
#include "SkColorFilter.h"
#include "SkSurface.h"
#include "gl/GrGLTypes.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

void LayerDrawable::onDraw(SkCanvas* canvas) {
    Layer* layer = mLayerUpdater->backingLayer();
    if (layer) {
        DrawLayer(canvas->getGrContext(), canvas, layer);
    }
}

bool LayerDrawable::DrawLayer(GrContext* context, SkCanvas* canvas, Layer* layer,
                              const SkRect* dstRect) {
    if (context == nullptr) {
        SkDEBUGF(("Attempting to draw LayerDrawable into an unsupported surface"));
        return false;
    }
    // transform the matrix based on the layer
    SkMatrix layerTransform = layer->getTransform();
    sk_sp<SkImage> layerImage = layer->getImage();
    const int layerWidth = layer->getWidth();
    const int layerHeight = layer->getHeight();

    if (layerImage) {
        SkMatrix textureMatrixInv;
        textureMatrixInv = layer->getTexTransform();
        // TODO: after skia bug https://bugs.chromium.org/p/skia/issues/detail?id=7075 is fixed
        // use bottom left origin and remove flipV and invert transformations.
        SkMatrix flipV;
        flipV.setAll(1, 0, 0, 0, -1, 1, 0, 0, 1);
        textureMatrixInv.preConcat(flipV);
        textureMatrixInv.preScale(1.0f / layerWidth, 1.0f / layerHeight);
        textureMatrixInv.postScale(layerWidth, layerHeight);
        SkMatrix textureMatrix;
        if (!textureMatrixInv.invert(&textureMatrix)) {
            textureMatrix = textureMatrixInv;
        }

        SkMatrix matrix;
        if (dstRect) {
            // Destination rectangle is set only when we are trying to read back the content
            // of the layer. In this case we don't want to apply layer transform.
            matrix = textureMatrix;
        } else {
            matrix = SkMatrix::Concat(layerTransform, textureMatrix);
        }

        SkPaint paint;
        paint.setAlpha(layer->getAlpha());
        paint.setBlendMode(layer->getMode());
        paint.setColorFilter(layer->getColorSpaceWithFilter());
        if (layer->getForceFilter()) {
            paint.setFilterQuality(kLow_SkFilterQuality);
        }

        const bool nonIdentityMatrix = !matrix.isIdentity();
        if (nonIdentityMatrix) {
            canvas->save();
            canvas->concat(matrix);
        }
        if (dstRect) {
            SkMatrix matrixInv;
            if (!matrix.invert(&matrixInv)) {
                matrixInv = matrix;
            }
            SkRect srcRect = SkRect::MakeIWH(layerWidth, layerHeight);
            matrixInv.mapRect(&srcRect);
            SkRect skiaDestRect = *dstRect;
            matrixInv.mapRect(&skiaDestRect);
            canvas->drawImageRect(layerImage.get(), srcRect, skiaDestRect, &paint,
                                  SkCanvas::kFast_SrcRectConstraint);
        } else {
            canvas->drawImage(layerImage.get(), 0, 0, &paint);
        }
        // restore the original matrix
        if (nonIdentityMatrix) {
            canvas->restore();
        }
    }

    return layerImage != nullptr;
}

};  // namespace skiapipeline
};  // namespace uirenderer
};  // namespace android
