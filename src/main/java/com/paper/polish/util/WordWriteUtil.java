package com.paper.polish.util;

import com.paper.polish.entity.Paragraph;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

@Slf4j
public class WordWriteUtil {

    public static void rewriteDocument(InputStream originalStream, OutputStream outputStream,
                                        List<Paragraph> paragraphs) {
        try (XWPFDocument document = new XWPFDocument(originalStream)) {
            int paraIndex = 0;
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    replaceParagraphText(paragraphs, paraIndex, (XWPFParagraph) element);
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

    private static void replaceParagraphText(List<Paragraph> paragraphs, int paraIndex, XWPFParagraph para) {
        Paragraph target = null;
        for (Paragraph p : paragraphs) {
            if (p.getParagraphIndex() != null && p.getParagraphIndex() == paraIndex
                    && "replaced".equals(p.getStatus())
                    && "text".equals(p.getContentType())) {
                target = p;
                break;
            }
        }
        if (target == null) {
            return;
        }

        String newText = target.getCurrentText();
        if (newText == null) {
            return;
        }

        List<XWPFRun> runs = para.getRuns();
        if (runs == null || runs.isEmpty()) {
            return;
        }

        if (runs.size() == 1) {
            runs.get(0).setText(newText, 0);
            return;
        }

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
