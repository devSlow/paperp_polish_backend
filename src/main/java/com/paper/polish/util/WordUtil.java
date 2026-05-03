package com.paper.polish.util;

import com.paper.polish.entity.Paragraph;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSpacing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDecimalNumber;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class WordUtil {

    public interface ImageUploader {
        String uploadImage(String paperId, String imageName, byte[] data, String contentType);
    }

    public static List<Paragraph> parseParagraphs(InputStream inputStream, String paperId, ImageUploader uploader) {
        List<Paragraph> paragraphs = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            int index = 0;
            int abstractIndex = -1;
            List<IBodyElement> elements = document.getBodyElements();

            for (int i = 0; i < elements.size(); i++) {
                IBodyElement element = elements.get(i);
                if (element instanceof XWPFParagraph) {
                    String t = ((XWPFParagraph) element).getText();
                    if (t != null && t.trim().matches("^(摘要|Abstract|ABSTRACT)\\s*$")) {
                        abstractIndex = index;
                    }
                    index++;
                } else if (element instanceof XWPFTable) {
                    index++;
                }
            }

            index = 0;
            for (IBodyElement element : elements) {
                if (element instanceof XWPFParagraph) {
                    boolean beforeAbstract = abstractIndex >= 0 && index < abstractIndex;
                    paragraphs.add(buildParagraph((XWPFParagraph) element, index, paperId, uploader, beforeAbstract));
                    index++;
                } else if (element instanceof XWPFTable) {
                    boolean beforeAbstract = abstractIndex >= 0 && index < abstractIndex;
                    paragraphs.add(buildTableParagraph((XWPFTable) element, index, paperId, beforeAbstract));
                    index++;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Word 文档解析失败: " + e.getMessage(), e);
        }
        return paragraphs;
    }

    private static Paragraph buildParagraph(XWPFParagraph para, int index, String paperId, ImageUploader uploader, boolean beforeAbstract) {
        Paragraph p = new Paragraph();
        p.setPaperId(paperId);
        p.setParagraphIndex(index);
        p.setLocationType(beforeAbstract ? "cover" : "body");
        p.setStatus("original");

        String text = para.getText();
        boolean isHeading = false;

        if (!beforeAbstract) {

        // 优先级1: outlineLvl（Word 大纲级别，最可靠）
        Integer outlineLvl = getOutlineLevel(para);
        if (outlineLvl != null && outlineLvl >= 0 && outlineLvl <= 5) {
            isHeading = true;
        }

        // 优先级2: Style 包含 Heading
        if (!isHeading) {
            String style = para.getStyle();
            if (style != null && (style.contains("Heading") || style.contains("heading"))) {
                isHeading = true;
            }
        }

        // 优先级3: 文本模式匹配（中文论文编号、章节名）
        if (!isHeading) {
            isHeading = isHeadingByText(text);
        }

        // 优先级4: 加粗 + 大字号
        if (!isHeading) {
            isHeading = isHeadingByFormat(para);
        }

        } // end if (!beforeAbstract)

        boolean hasImage = hasImage(para);
        List<String> imageUrls = hasImage ? extractAllImages(para, paperId, uploader) : new ArrayList<>();

        if (!imageUrls.isEmpty()) {
            p.setContentType("image");
            p.setType(isHeading ? "heading" : "paragraph");
            p.setImageUrl(String.join("|||", imageUrls));
            p.setOriginalText(text != null ? text : "");
            p.setCurrentText(p.getOriginalText());
            p.setHtmlContent(buildImageHtml(para, imageUrls));
            p.setCanRewrite(false);
        } else if (text == null || text.trim().isEmpty()) {
            p.setContentType("empty");
            p.setType(isHeading ? "heading" : "paragraph");
            p.setOriginalText("");
            p.setCurrentText("");
            p.setCanRewrite(false);
        } else {
            p.setContentType("text");
            p.setType(isHeading ? "heading" : "paragraph");
            p.setOriginalText(text);
            p.setCurrentText(text);
            p.setHtmlContent(buildTextHtml(para));
            p.setCanRewrite(!isHeading && !hasDrawing(para));
        }

        return p;
    }

    private static Paragraph buildTableParagraph(XWPFTable table, int index, String paperId, boolean beforeAbstract) {
        Paragraph p = new Paragraph();
        p.setPaperId(paperId);
        p.setParagraphIndex(index);
        p.setLocationType(beforeAbstract ? "cover" : "body");
        p.setStatus("original");
        p.setType("table");
        p.setContentType("table");
        p.setCanRewrite(false);

        StringBuilder allText = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText();
                if (cellText != null && !cellText.trim().isEmpty()) {
                    allText.append(cellText).append(" ");
                }
            }
        }
        p.setOriginalText(allText.toString().trim());
        p.setCurrentText(p.getOriginalText());
        p.setHtmlContent(buildTableHtml(table));

        return p;
    }

    private static String buildTextHtml(XWPFParagraph para) {
        StringBuilder sb = new StringBuilder();
        String paraStyle = buildParaCss(para);

        sb.append("<p style=\"").append(paraStyle).append("\">");

        List<XWPFRun> runs = para.getRuns();
        if (runs != null && !runs.isEmpty()) {
            for (XWPFRun run : runs) {
                String runCss = buildRunCss(run);
                String runText = run.text();
                if (runText == null || runText.isEmpty()) continue;

                String escaped = escapeHtml(runText);
                boolean needSpan = runCss.length() > 0;

                if (needSpan) {
                    sb.append("<span style=\"").append(runCss).append("\">");
                }
                sb.append(escaped);
                if (needSpan) {
                    sb.append("</span>");
                }
            }
        } else {
            sb.append(escapeHtml(para.getText()));
        }

        sb.append("</p>");
        return sb.toString();
    }

    private static String buildImageHtml(XWPFParagraph para, List<String> urls) {
        StringBuilder sb = new StringBuilder();
        String paraStyle = buildParaCss(para);

        sb.append("<div style=\"").append(paraStyle).append("; text-align: center;\">");
        for (String url : urls) {
            sb.append("<img src=\"").append(escapeAttr(url)).append("\" style=\"max-width: 100%; height: auto; margin: 8px 0;\" />");
        }
        String text = para.getText();
        if (text != null && !text.trim().isEmpty()) {
            sb.append("<p style=\"font-size: 10.5pt; color: #666; margin-top: 4px;\">").append(escapeHtml(text)).append("</p>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String buildTableHtml(XWPFTable table) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"margin: 8px 0; overflow-x: auto;\">");
        sb.append("<table style=\"width: 100%; border-collapse: collapse; font-size: 10.5pt;\">");

        List<XWPFTableRow> rows = table.getRows();
        for (int ri = 0; ri < rows.size(); ri++) {
            XWPFTableRow row = rows.get(ri);
            sb.append("<tr>");
            for (XWPFTableCell cell : row.getTableCells()) {
                String border = "border: 1px solid #ccc; padding: 6px 8px; text-align: left; vertical-align: top;";
                if (ri == 0) {
                    border += " background-color: #f5f5f5; font-weight: 500;";
                }
                sb.append("<td style=\"").append(border).append("\">");
                for (XWPFParagraph cp : cell.getParagraphs()) {
                    String cellText = cp.getText();
                    if (cellText != null && !cellText.isEmpty()) {
                        sb.append(escapeHtml(cellText));
                    }
                    sb.append("<br/>");
                }
                sb.append("</td>");
            }
            sb.append("</tr>");
        }

        sb.append("</table></div>");
        return sb.toString();
    }

    private static String buildParaCss(XWPFParagraph para) {
        StringBuilder sb = new StringBuilder();
        sb.append("margin: 0; padding: 0;");

        String style = para.getStyle();
        if (style != null) {
            if (style.contains("Heading1") || style.equals("1")) {
                sb.append("font-size: 22pt; font-weight: bold; margin-top: 12pt; margin-bottom: 6pt;");
            } else if (style.contains("Heading2") || style.equals("2")) {
                sb.append("font-size: 16pt; font-weight: bold; margin-top: 10pt; margin-bottom: 4pt;");
            } else if (style.contains("Heading3") || style.equals("3")) {
                sb.append("font-size: 14pt; font-weight: bold; margin-top: 8pt; margin-bottom: 3pt;");
            } else if (style.contains("Heading")) {
                sb.append("font-weight: bold; margin-top: 8pt; margin-bottom: 3pt;");
            }
        }

        CTPPr ppr = para.getCTP().getPPr();
        if (ppr != null) {
            if (ppr.getJc() != null) {
                String align = ppr.getJc().getVal().toString().toLowerCase();
                String cssAlign = "both".equals(align) ? "justify" : align;
                sb.append("text-align: ").append(cssAlign).append(";");
            }
            CTSpacing spacing = ppr.getSpacing();
            if (spacing != null) {
                if (spacing.getLine() != null) {
                    float lh = Float.parseFloat(spacing.getLine().toString()) / 240f;
                    sb.append("line-height: ").append(Math.round(lh * 100) / 100f).append(";");
                }
                if (spacing.getBefore() != null) {
                    sb.append("margin-top: ").append(Math.round(Float.parseFloat(spacing.getBefore().toString()) / 20f)).append("pt;");
                }
                if (spacing.getAfter() != null) {
                    sb.append("margin-bottom: ").append(Math.round(Float.parseFloat(spacing.getAfter().toString()) / 20f)).append("pt;");
                }
            }
            if (ppr.getInd() != null) {
                if (ppr.getInd().getFirstLine() != null) {
                    sb.append("text-indent: ").append(Math.round(Float.parseFloat(ppr.getInd().getFirstLine().toString()) / 20f)).append("pt;");
                }
                if (ppr.getInd().getLeft() != null) {
                    sb.append("padding-left: ").append(Math.round(Float.parseFloat(ppr.getInd().getLeft().toString()) / 20f)).append("pt;");
                }
            }
        }

        if (!sb.toString().contains("line-height:")) {
            sb.append("line-height: 1.8;");
        }
        if (!sb.toString().contains("font-size:")) {
            sb.append("font-size: 12pt;");
        }

        return sb.toString();
    }

    private static String buildRunCss(XWPFRun run) {
        StringBuilder sb = new StringBuilder();
        if (run.isBold()) sb.append("font-weight: bold;");
        if (run.isItalic()) sb.append("font-style: italic;");
        if (run.isStrikeThrough()) sb.append("text-decoration: line-through;");
        if (run.getUnderline() != UnderlinePatterns.NONE) sb.append("text-decoration: underline;");
        String color = run.getColor();
        if (color != null && !color.isEmpty() && !"000000".equals(color) && !"auto".equals(color)) {
            sb.append("color: #").append(color).append(";");
        }
        String fontFamily = run.getFontFamily();
        if (fontFamily != null && !fontFamily.isEmpty()) sb.append("font-family: '").append(fontFamily).append("';");
        int fontSize = run.getFontSize();
        if (fontSize > 0) sb.append("font-size: ").append(fontSize).append("pt;");
        return sb.toString();
    }

    private static boolean hasImage(XWPFParagraph para) {
        try {
            String xml = para.getCTP().xmlText();
            return xml.contains("<w:drawing") || xml.contains("<w:pict") || xml.contains(":blip");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasDrawing(XWPFParagraph para) {
        try {
            String xml = para.getCTP().xmlText();
            return xml != null && xml.contains("<w:drawing");
        } catch (Exception e) {
            return false;
        }
    }

    private static List<String> extractAllImages(XWPFParagraph para, String paperId, ImageUploader uploader) {
        List<String> urls = new ArrayList<>();

        for (XWPFRun run : para.getRuns()) {
            for (XWPFPicture pic : run.getEmbeddedPictures()) {
                try {
                    byte[] data = pic.getPictureData().getData();
                    String ext = pic.getPictureData().suggestFileExtension();
                    if (ext == null) ext = "png";
                    String imageName = System.currentTimeMillis() + "_embed_" + urls.size() + "." + ext;
                    String contentType = "image/" + ("jpg".equals(ext) ? "jpeg" : ext);
                    String url = uploader.uploadImage(paperId, imageName, data, contentType);
                    if (url != null) urls.add(url);
                } catch (Exception e) {
                    log.trace("上传嵌入图片失败: {}", e.getMessage());
                }
            }
        }

        if (!urls.isEmpty()) return urls;

        try {
            XWPFDocument doc = null;
            try {
                java.lang.reflect.Field partField = para.getClass().getDeclaredField("part");
                partField.setAccessible(true);
                doc = (XWPFDocument) partField.get(para);
            } catch (Exception ignored) {}

            if (doc != null) {
                CTP ctp = para.getCTP();
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("r:embed=\"([^\"]+)\"|r:id=\"([^\"]+)\"");
                java.util.regex.Matcher m = pattern.matcher(ctp.xmlText());
                while (m.find()) {
                    String embedId = m.group(1) != null ? m.group(1) : m.group(2);
                    try {
                        org.apache.poi.openxml4j.opc.PackageRelationship rel = doc.getPackagePart()
                                .getRelationships().getRelationshipByID(embedId);
                        if (rel == null) continue;
                        org.apache.poi.openxml4j.opc.PackagePart imagePart = doc.getPackagePart().getRelatedPart(rel);
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        try (java.io.InputStream is = imagePart.getInputStream()) {
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                        }
                        byte[] imageData = baos.toByteArray();
                        String partName = imagePart.getPartName().getName();
                        String ext = partName.contains(".") ? partName.substring(partName.lastIndexOf(".") + 1) : "png";
                        if ("jpeg".equals(ext)) ext = "jpg";
                        String imageName = System.currentTimeMillis() + "_draw_" + urls.size() + "." + ext;
                        String contentType = "image/" + ("jpg".equals(ext) ? "jpeg" : ext);
                        String url = uploader.uploadImage(paperId, imageName, imageData, contentType);
                        if (url != null) urls.add(url);
                    } catch (Exception e) {
                        log.trace("提取 drawing 图片失败: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.trace("提取图片失败: {}", e.getMessage());
        }

        return urls;
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String escapeAttr(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static Integer getOutlineLevel(XWPFParagraph para) {
        try {
            CTPPr ppr = para.getCTP().getPPr();
            if (ppr != null && ppr.isSetOutlineLvl()) {
                CTDecimalNumber lvl = ppr.getOutlineLvl();
                if (lvl != null) return lvl.getVal().intValue();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isHeadingByText(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 80) return false;

        if (t.matches("^第[一二三四五六七八九十百零\\d]+[章节篇部分].*")) return true;
        if (t.matches("^[一二三四五六七八九十百]+[、．.].*")) return true;
        if (t.matches("^[（(][一二三四五六七八九十]+[）)].*")) return true;
        if (t.matches("^\\d+(\\.\\d+)+[\\s　].*")) return true;
        if (t.matches("^\\d+[\\s　]\\S+.*") && t.length() < 40) return true;
        if (t.matches("^\\d+(\\.\\d+)+$")) return true;
        if (t.matches("^(摘要|Abstract|ABSTRACT)\\s*$")) return true;
        if (t.matches("^(参考文献|致谢|附录\\s*[A-Z0-9]*)?[\\s：:]*$") && t.length() > 0) return false;
        if (t.matches("^(参考文献|致谢|附录|引言|绪论|结论|目录|前言|后记|Keywords|KEYWORDS|关键词)[\\s：:]*$")) return true;
        return false;
    }

    private static boolean isHeadingByFormat(XWPFParagraph para) {
        String text = para.getText();
        if (text == null || text.trim().isEmpty() || text.trim().length() > 80) return false;

        List<XWPFRun> runs = para.getRuns();
        if (runs == null || runs.isEmpty()) return false;

        boolean hasBold = false;
        int maxSize = 0;
        int textRunCount = 0;

        for (XWPFRun run : runs) {
            String runText = run.text();
            if (runText == null || runText.trim().isEmpty()) continue;
            textRunCount++;
            if (run.isBold()) hasBold = true;
            int fontSize = run.getFontSize();
            if (fontSize > 0 && fontSize > maxSize) maxSize = fontSize;
        }

        if (textRunCount == 0) return false;

        return hasBold && maxSize >= 14;
    }
}
