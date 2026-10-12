#include "ocr.h"

#include <tesseract/baseapi.h>
#include <tesseract/resultiterator.h>

#include <chrono>

namespace mx {

TextRecognizer::TextRecognizer() = default;

TextRecognizer::~TextRecognizer() {
    if (api_) api_->End();
}

bool TextRecognizer::init(const std::string & tessdata, const std::string & languages, std::string & error) {
    api_ = std::make_unique<tesseract::TessBaseAPI>();
    // Never look for configs or data anywhere but the app-provided directory.
    if (api_->Init(tessdata.c_str(), languages.c_str(), tesseract::OEM_LSTM_ONLY) != 0) {
        error = "text recognition data could not be loaded for: " + languages;
        api_.reset();
        return false;
    }
    api_->SetVariable("debug_file", "/dev/null");
    api_->SetVariable("preserve_interword_spaces", "1");
    return true;
}

bool TextRecognizer::recognize(const unsigned char * pixels, int width, int height, int bpp, int bpl, int psm,
                               OcrResult & out, std::string & error) {
    if (!api_) { error = "not initialised"; return false; }
    if (width <= 0 || height <= 0 || (bpp != 1 && bpp != 3 && bpp != 4) || bpl < width * bpp) {
        error = "invalid image";
        return false;
    }
    const auto t0 = std::chrono::steady_clock::now();
    api_->SetPageSegMode(static_cast<tesseract::PageSegMode>(psm));
    api_->SetImage(pixels, width, height, bpp, bpl);
    if (api_->Recognize(nullptr) != 0) {
        error = "recognition failed";
        api_->Clear();
        return false;
    }

    out = {};
    std::unique_ptr<tesseract::ResultIterator> it(api_->GetIterator());
    int block = -1, para = -1;
    float conf_sum = 0;
    if (it) {
        it->Begin();
        do {
            if (it->IsAtBeginningOf(tesseract::RIL_BLOCK)) ++block;
            if (it->IsAtBeginningOf(tesseract::RIL_PARA)) {
                ++para;
                if (!out.text.empty()) out.text += "\n";
            }
            std::unique_ptr<char[]> txt(it->GetUTF8Text(tesseract::RIL_TEXTLINE));
            if (!txt) continue;
            std::string line(txt.get());
            while (!line.empty() && (line.back() == '\n' || line.back() == ' ')) line.pop_back();
            if (line.empty()) continue;
            int x1, y1, x2, y2;
            it->BoundingBox(tesseract::RIL_TEXTLINE, &x1, &y1, &x2, &y2);
            const float conf = it->Confidence(tesseract::RIL_TEXTLINE);
            out.lines.push_back({line, conf, x1, y1, x2 - x1, y2 - y1, block, para});
            conf_sum += conf;
            out.text += line;
            out.text += "\n";
        } while (it->Next(tesseract::RIL_TEXTLINE));
    }
    while (!out.text.empty() && out.text.back() == '\n') out.text.pop_back();
    out.mean_confidence = out.lines.empty() ? 0.0f : conf_sum / static_cast<float>(out.lines.size());
    out.elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
    api_->Clear(); // drop the image and intermediate buffers immediately
    return true;
}

} // namespace mx
