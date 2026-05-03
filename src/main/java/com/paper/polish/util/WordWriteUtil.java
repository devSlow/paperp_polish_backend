package com.paper.polish.util;

import com.paper.polish.entity.Paragraph;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class WordWriteUtil {

    public static void rewriteDocument(InputStream originalStream, OutputStream outputStream,
                                        List<Paragraph> paragraphs) {
        Map<Integer, String> replacedTexts = paragraphs.stream()
                .filter(p -> "replaced".equals(p.getStatus()) && "text".equals(p.getContentType()))
                .filter(p -> p.getCurrentText() != null)
                .collect(Collectors.toMap(Paragraph::getParagraphIndex, Paragraph::getCurrentText, (a, b) -> a));

        try (XWPFDocument document = new XWPFDocument(originalStream)) {
            int paraIndex = 0;
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    String newText = replacedTexts.get(paraIndex);
                    if (newText != null) {
                        replaceRunsText((XWPFParagraph) element, newText);
                    }
                    paraIndex++;
                } else if (element instanceof XWPFTable) {
                    paraIndex++;
                }
            }
            document.write(outputStream);
        } catch (Exception e) {
            throw new RuntimeException("Word 回写失败: " + e.getMessage(), e);
        }
    }

    private static void replaceRunsText(XWPFParagraph para, String newText) {
        List<XWPFRun> runs = para.getRuns();
        if (runs == null || runs.isEmpty()) return;

        runs.get(0).setText(newText, 0);

        for (int i = 1; i < runs.size(); i++) {
            CTR ctr = runs.get(i).getCTR();
            int sizeOfTArray = ctr.sizeOfTArray();
            for (int j = sizeOfTArray - 1; j >= 0; j--) {
                ctr.removeT(j);
            }
        }
    }
}
