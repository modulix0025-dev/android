// Local text recognition on top of Tesseract (LSTM engine).
#pragma once

#include <memory>
#include <string>
#include <vector>

namespace tesseract { class TessBaseAPI; }

namespace mx {

struct OcrLine {
    std::string text;
    float confidence; // 0..100
    int x, y, w, h;
    int block;        // block index, in Tesseract reading order
    int paragraph;    // paragraph index within the page
};

struct OcrResult {
    std::string text;        // full text in reading order (paragraphs separated by blank lines)
    float mean_confidence;   // 0..100
    std::vector<OcrLine> lines;
    int64_t elapsed_ms;
};

class TextRecognizer {
public:
    TextRecognizer();
    ~TextRecognizer();
    // `languages`: e.g. "ara+eng". `tessdata` must contain the .traineddata files.
    bool init(const std::string & tessdata, const std::string & languages, std::string & error);
    // Pixels: 1 (gray), 3 (RGB) or 4 (RGBA) bytes per pixel.
    bool recognize(const unsigned char * pixels, int width, int height, int bytes_per_pixel, int bytes_per_line,
                   int page_seg_mode, OcrResult & out, std::string & error);

private:
    std::unique_ptr<tesseract::TessBaseAPI> api_;
};

} // namespace mx
