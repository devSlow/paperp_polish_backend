package com.paper.polish.util;

import com.paper.polish.dto.PptGenerateDTO;
import org.apache.poi.xslf.usermodel.*;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

public class PptGenerateUtil {

    private static final Color TITLE_COLOR = new Color(0x1F, 0x38, 0x64);
    private static final Color BODY_COLOR = new Color(0x33, 0x33, 0x33);
    private static final Color ACCENT_COLOR = new Color(0x2E, 0x75, 0xB6);
    private static final Color SLIDE_BG = new Color(0xF5, 0xF5, 0xF5);

    public static byte[] generate(PptGenerateDTO dto) throws IOException {
        XMLSlideShow ppt = new XMLSlideShow();
        ppt.setPageSize(new Dimension(960, 540));

        XSLFSlideMaster master = ppt.getSlideMasters().get(0);
        XSLFSlideLayout titleLayout = master.getLayout(SlideLayout.TITLE);
        XSLFSlideLayout contentLayout = master.getLayout(SlideLayout.TITLE_AND_CONTENT);

        if (dto.getTitle() != null && !dto.getTitle().isEmpty()) {
            XSLFSlide titleSlide = ppt.createSlide(titleLayout);
            styleTitleSlide(titleSlide, dto.getTitle());
        }

        List<PptGenerateDTO.PptSlideDTO> slides = dto.getSlides();
        if (slides != null) {
            for (PptGenerateDTO.PptSlideDTO slideDto : slides) {
                XSLFSlide slide = ppt.createSlide(contentLayout);
                addContentSlide(slide, slideDto);
            }
        }

        XSLFSlide endSlide = ppt.createSlide(titleLayout);
        styleEndSlide(endSlide);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ppt.write(out);
        ppt.close();
        return out.toByteArray();
    }

    private static void styleTitleSlide(XSLFSlide slide, String title) {
        XSLFBackground bg = slide.getBackground();
        bg.setFillColor(ACCENT_COLOR);

        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape) {
                XSLFTextShape txShape = (XSLFTextShape) shape;
                if (txShape.getTextParagraphs().isEmpty()) continue;

                XSLFTextParagraph p = txShape.getTextParagraphs().get(0);
                if (p.getTextRuns().isEmpty()) continue;

                XSLFTextRun run = p.getTextRuns().get(0);
                run.setFontColor(Color.WHITE);
                run.setFontSize(44.0);
                run.setBold(true);
                run.setFontFamily("Microsoft YaHei");
                txShape.setText(title);
            }
        }
    }

    private static void styleEndSlide(XSLFSlide slide) {
        XSLFBackground bg = slide.getBackground();
        bg.setFillColor(ACCENT_COLOR);

        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape) {
                XSLFTextShape txShape = (XSLFTextShape) shape;
                if (txShape.getTextParagraphs().isEmpty()) continue;

                XSLFTextParagraph p = txShape.getTextParagraphs().get(0);
                if (p.getTextRuns().isEmpty()) continue;

                XSLFTextRun run = p.getTextRuns().get(0);
                run.setFontColor(Color.WHITE);
                run.setFontSize(36.0);
                run.setBold(true);
                run.setFontFamily("Microsoft YaHei");
                txShape.setText("谢谢观看");
            }
        }
    }

    private static void addContentSlide(XSLFSlide slide, PptGenerateDTO.PptSlideDTO dto) {
        XSLFBackground bg = slide.getBackground();
        bg.setFillColor(SLIDE_BG);

        for (XSLFShape shape : slide.getShapes()) {
            if (!(shape instanceof XSLFTextShape)) continue;
            XSLFTextShape txShape = (XSLFTextShape) shape;

            if (txShape.getTextParagraphs().isEmpty()) continue;

            XSLFTextRun firstRun = txShape.getTextParagraphs().get(0).getTextRuns().isEmpty()
                    ? null : txShape.getTextParagraphs().get(0).getTextRuns().get(0);

            if (firstRun != null && firstRun.getFontSize() != null && firstRun.getFontSize() > 30) {
                styleSlideTitle(txShape, dto.getTitle());
            } else {
                styleSlideBody(txShape, dto);
            }
        }
    }

    private static void styleSlideTitle(XSLFTextShape shape, String title) {
        shape.clearText();
        XSLFTextParagraph p = shape.addNewTextParagraph();
        p.setIndent(0.0);
        p.setLeftMargin(0.0);
        XSLFTextRun run = p.addNewTextRun();
        run.setText(title != null ? title : "");
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(32.0);
        run.setBold(true);
        run.setFontColor(TITLE_COLOR);
    }

    private static void styleSlideBody(XSLFTextShape shape, PptGenerateDTO.PptSlideDTO dto) {
        shape.clearText();
        List<String> bullets = dto.getBullets();
        if (bullets == null || bullets.isEmpty()) return;

        for (String bullet : bullets) {
            XSLFTextParagraph p = shape.addNewTextParagraph();
            p.setIndent(18.0);
            p.setLeftMargin(18.0);
            p.setSpaceAfter(6.0);
            XSLFTextRun run = p.addNewTextRun();
            run.setText("• " + bullet);
            run.setFontFamily("Microsoft YaHei");
            run.setFontSize(18.0);
            run.setFontColor(BODY_COLOR);
        }
    }
}
