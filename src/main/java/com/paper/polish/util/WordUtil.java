package com.paper.polish.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paper.polish.entity.Paragraph;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class WordUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public interface ImageUploader {
        String uploadImage(String paperId, String imageName, byte[] data, String contentType);
    }

    public static List<Paragraph> parseParagraphs(InputStream inputStream, String paperId, ImageUploader uploader) {
        List<Paragraph> paragraphs = new ArrayList<>();
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            int index = 0;

            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph) {
                    XWPFParagraph para = (XWPFParagraph) element;
                    Paragraph p = buildBodyParagraph(para, index, paperId, uploader);
                    paragraphs.add(p);
                    index++;
                } else if (element instanceof XWPFTable) {
                    XWPFTable table = (XWPFTable) element;
                    Paragraph p = buildTableParagraph(table, index, paperId);
                    paragraphs.add(p);
                    index++;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Word 文档解析失败: " + e.getMessage(), e);
        }
        return paragraphs;
    }

    private static Paragraph buildBodyParagraph(XWPFParagraph para, int index, String paperId,
                                                 ImageUploader uploader) {
        Paragraph p = new Paragraph();
        p.setPaperId(paperId);
        p.setParagraphIndex(index);
        p.setLocationType("body");
        p.setStatus("original");

        String style = para.getStyle();
        boolean isHeading = style != null && (style.contains("Heading") || style.contains("heading"));

        List<String> imageUrls = extractImages(para, paperId, uploader);

        if (!imageUrls.isEmpty()) {
            p.setContentType("image");
            p.setType(isHeading ? "heading" : "paragraph");
            p.setImageUrl(String.join("|||", imageUrls));
            p.setOriginalText(para.getText());
            p.setCurrentText(para.getText());
            p.setCanRewrite(false);
        } else if (isCodeBlock(para)) {
            p.setContentType("code");
            p.setType("paragraph");
            p.setOriginalText(para.getText());
            p.setCurrentText(para.getText());
            p.setCanRewrite(false);
        } else {
            p.setContentType("text");
            p.setType(detectType(para));
            p.setOriginalText(para.getText());
            p.setCurrentText(para.getText());
            p.setCanRewrite(shouldRewrite(para));
        }

        p.setStyleData(extractStyle(para));

        return p;
    }

    private static Paragraph buildTableParagraph(XWPFTable table, int index, String paperId) {
        Paragraph p = new Paragraph();
        p.setPaperId(paperId);
        p.setParagraphIndex(index);
        p.setLocationType("body");
        p.setStatus("original");
        p.setType("table");
        p.setContentType("table");
        p.setCanRewrite(false);

        List<List<List<String>>> tableData = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<List<String>> rowData = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                List<String> cellParas = new ArrayList<>();
                for (XWPFParagraph para : cell.getParagraphs()) {
                    String text = para.getText();
                    if (text != null && !text.trim().isEmpty()) {
                        cellParas.add(text);
                    }
                }
                rowData.add(cellParas);
            }
            tableData.add(rowData);
        }

        try {
            p.setTableData(MAPPER.writeValueAsString(tableData));
        } catch (Exception e) {
            p.setTableData("[]");
        }

        StringBuilder allText = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                allText.append(cell.getText()).append(" ");
            }
        }
        p.setOriginalText(allText.toString().trim());
        p.setCurrentText(p.getOriginalText());

        return p;
    }

    private static List<String> extractImages(XWPFParagraph para, String paperId, ImageUploader uploader) {
        List<String> urls = new ArrayList<>();
        try {
            for (XWPFRun run : para.getRuns()) {
                for (XWPFPicture pic : run.getEmbeddedPictures()) {
                    try {
                        byte[] data = pic.getPictureData().getData();
                        String ext = pic.getPictureData().suggestFileExtension();
                        if (ext == null) ext = "png";
                        String imageName = System.currentTimeMillis() + "_embed_" + urls.size() + "." + ext;
                        String contentType = "image/" + ext;
                        if ("jpg".equals(ext)) contentType = "image/jpeg";
                        String url = uploader.uploadImage(paperId, imageName, data, contentType);
                        if (url != null) urls.add(url);
                    } catch (Exception e) {
                        log.trace("上传嵌入图片失败: {}", e.getMessage());
                    }
                }
            }

            if (urls.isEmpty()) {
                List<String> drawingUrls = extractDrawingImages(para, paperId, uploader);
                urls.addAll(drawingUrls);
            }

            if (urls.isEmpty()) {
                List<String> inlineUrls = extractInlineImages(para, paperId, uploader);
                urls.addAll(inlineUrls);
            }
        } catch (Exception e) {
            log.trace("提取图片失败: {}", e.getMessage());
        }
        return urls;
    }

    private static List<String> extractDrawingImages(XWPFParagraph para, String paperId, ImageUploader uploader) {
        List<String> urls = new ArrayList<>();
        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp = para.getCTP();
            org.apache.xmlbeans.XmlObject[] drawings = ctp.selectPath(
                    "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' " +
                    "declare namespace wp='http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing' " +
                    "declare namespace a='http://schemas.openxmlformats.org/drawingml/2006/main' " +
                    "declare namespace r='http://schemas.openxmlformats.org/officeDocument/2006/relationships' " +
                    ".//w:drawing/wp:inline/a:graphic/a:graphicData/pic:pic/pic:blipFill/a:blip");
            org.apache.xmlbeans.XmlObject[] anchorDrawings = ctp.selectPath(
                    "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' " +
                    "declare namespace wp='http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing' " +
                    "declare namespace a='http://schemas.openxmlformats.org/drawingml/2006/main' " +
                    "declare namespace r='http://schemas.openxmlformats.org/officeDocument/2006/relationships' " +
                    ".//w:drawing/wp:anchor/a:graphic/a:graphicData/pic:pic/pic:blipFill/a:blip");

            List<org.apache.xmlbeans.XmlObject> allBlips = new ArrayList<>();
            for (org.apache.xmlbeans.XmlObject o : drawings) allBlips.add(o);
            for (org.apache.xmlbeans.XmlObject o : anchorDrawings) allBlips.add(o);

            org.apache.poi.xwpf.usermodel.XWPFDocument doc = null;
            try {
                java.lang.reflect.Field partField = para.getClass().getDeclaredField("part");
                partField.setAccessible(true);
                org.apache.poi.xwpf.usermodel.XWPFDocument tmp = (org.apache.poi.xwpf.usermodel.XWPFDocument) partField.get(para);
                doc = tmp;
            } catch (Exception ignored) {}

            for (org.apache.xmlbeans.XmlObject blip : allBlips) {
                try {
                    String embedId = null;
                    {
                        String xmlText = blip.xmlText();
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("r:embed=\"([^\"]+)\"").matcher(xmlText);
                        if (m.find()) embedId = m.group(1);
                    }

                    if (embedId == null || embedId.isEmpty()) continue;

                    if (doc == null) {
                        try {
                            java.lang.reflect.Method getPart = para.getClass().getSuperclass().getDeclaredMethod("getPart");
                            getPart.setAccessible(true);
                            Object part = getPart.invoke(para);
                            if (part instanceof org.apache.poi.xwpf.usermodel.XWPFDocument) {
                                doc = (org.apache.poi.xwpf.usermodel.XWPFDocument) part;
                            }
                        } catch (Exception ignored2) {}
                    }
                    if (doc == null) continue;

                    org.apache.poi.openxml4j.opc.PackageRelationship rel = doc.getPackagePart()
                            .getRelationships().getRelationshipByID(embedId);
                    if (rel == null) continue;

                    org.apache.poi.openxml4j.opc.PackagePart imagePart = doc.getPackagePart()
                            .getRelatedPart(rel);
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    try (java.io.InputStream is = imagePart.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            baos.write(buf, 0, n);
                        }
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
        } catch (Exception e) {
            log.trace("提取 drawing 图片失败: {}", e.getMessage());
        }
        return urls;
    }

    private static List<String> extractInlineImages(XWPFParagraph para, String paperId, ImageUploader uploader) {
        List<String> urls = new ArrayList<>();
        try {
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP ctp = para.getCTP();
            org.apache.xmlbeans.XmlObject[] inlineElements = ctp.selectPath(
                    "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' " +
                    "declare namespace wp='http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing' " +
                    ".//wp:inline");

            org.apache.poi.xwpf.usermodel.XWPFDocument doc = null;
            try {
                java.lang.reflect.Field partField = para.getClass().getDeclaredField("part");
                partField.setAccessible(true);
                doc = (org.apache.poi.xwpf.usermodel.XWPFDocument) partField.get(para);
            } catch (Exception ignored) {}

            for (org.apache.xmlbeans.XmlObject inline : inlineElements) {
                try {
                    String xmlText = inline.xmlText();
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("r:embed=\"([^\"]+)\"").matcher(xmlText);
                    if (!m.find()) continue;
                    String embedId = m.group(1);
                    if (doc == null) continue;

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
                    String imageName = System.currentTimeMillis() + "_inline_" + urls.size() + "." + ext;
                    String contentType = "image/" + ("jpg".equals(ext) ? "jpeg" : ext);
                    String url = uploader.uploadImage(paperId, imageName, imageData, contentType);
                    if (url != null) urls.add(url);
                } catch (Exception e) {
                    log.trace("提取 inline 图片失败: {}", e.getMessage());
                }
            }

            if (urls.isEmpty()) {
                org.apache.xmlbeans.XmlObject[] pictElements = ctp.selectPath(
                        "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' " +
                        "declare namespace v='urn:schemas-microsoft-com:vml' " +
                        "declare namespace r='http://schemas.openxmlformats.org/officeDocument/2006/relationships' " +
                        ".//w:pict/v:imagedata");
                if (pictElements.length == 0) {
                    pictElements = ctp.selectPath(
                            "declare namespace w='http://schemas.openxmlformats.org/wordprocessingml/2006/main' " +
                            ".//w:pict");
                }

                if (doc == null) {
                    try {
                        java.lang.reflect.Field partField = para.getClass().getDeclaredField("part");
                        partField.setAccessible(true);
                        doc = (org.apache.poi.xwpf.usermodel.XWPFDocument) partField.get(para);
                    } catch (Exception ignored) {}
                }

                for (org.apache.xmlbeans.XmlObject pict : pictElements) {
                    try {
                        String xmlText = pict.xmlText();
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("r:id=\"([^\"]+)\"").matcher(xmlText);
                        if (!m.find()) {
                            m = java.util.regex.Pattern.compile("r:embed=\"([^\"]+)\"").matcher(xmlText);
                        }
                        if (!m.find()) continue;
                        String rid = m.group(1);
                        if (doc == null) continue;

                        org.apache.poi.openxml4j.opc.PackageRelationship rel = doc.getPackagePart()
                                .getRelationships().getRelationshipByID(rid);
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
                        String imageName = System.currentTimeMillis() + "_pict_" + urls.size() + "." + ext;
                        String contentType = "image/" + ("jpg".equals(ext) ? "jpeg" : ext);
                        String url = uploader.uploadImage(paperId, imageName, imageData, contentType);
                        if (url != null) urls.add(url);
                    } catch (Exception e) {
                        log.trace("提取 pict 图片失败: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.trace("提取 inline/vml 图片失败: {}", e.getMessage());
        }
        return urls;
    }

    private static boolean hasDrawingElement(XWPFParagraph para) {
        try {
            String xml = para.getCTP().xmlText();
            return xml != null && xml.contains("<w:drawing");
        } catch (Exception e) {
            return false;
        }
    }

    private static String detectType(XWPFParagraph para) {
        String style = para.getStyle();
        if (style != null && (style.contains("Heading") || style.contains("heading")
                || style.startsWith("1") || style.startsWith("2"))) {
            return "heading";
        }
        return "paragraph";
    }

    private static boolean shouldRewrite(XWPFParagraph para) {
        String text = para.getText();
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String style = para.getStyle();
        if (style != null && (style.contains("Heading") || style.contains("heading"))) {
            return false;
        }
        for (XWPFRun run : para.getRuns()) {
            if (!run.getEmbeddedPictures().isEmpty()) {
                return false;
            }
        }
        if (hasDrawingElement(para)) {
            return false;
        }
        return true;
    }

    private static boolean isCodeBlock(XWPFParagraph para) {
        String style = para.getStyle();
        if (style != null) {
            String lower = style.toLowerCase();
            if (lower.contains("code") || lower.contains("source") || lower.contains("preformatted")
                    || lower.contains("noformat") || lower.contains("verbatim")) {
                return true;
            }
        }

        String text = para.getText();
        if (text == null || text.trim().isEmpty()) {
            return false;
        }

        for (XWPFRun run : para.getRuns()) {
            String fontFamily = run.getFontFamily();
            if (fontFamily != null) {
                String lower = fontFamily.toLowerCase();
                if (lower.contains("consolas") || lower.contains("courier") || lower.contains("monospace")
                        || lower.contains("menlo") || lower.contains("mono") || lower.contains("source code pro")
                        || lower.contains("fira code") || lower.contains("jetbrains")) {
                    return true;
                }
            }
        }

        int score = 0;
        if (text.contains("public ") || text.contains("private ") || text.contains("protected ")
                || text.contains("class ") || text.contains("interface ") || text.contains("extends ")
                || text.contains("implements ") || text.contains("@Override") || text.contains("@Table")
                || text.contains("@Entity") || text.contains("@Autowired") || text.contains("@Service")
                || text.contains("@Mapper") || text.contains("@Controller") || text.contains("@GetMapping")
                || text.contains("@PostMapping") || text.contains("@RequestMapping")
                || text.contains("void ") || text.contains("return ") || text.contains("new ")
                || text.contains("import ") || text.contains("package ")) {
            score += 2;
        }
        if (text.contains("String ") || text.contains("Integer ") || text.contains("Long ")
                || text.contains("Boolean ") || text.contains("int ") || text.contains("double ")
                || text.contains("float ") || text.contains("List<") || text.contains("Map<")) {
            score += 1;
        }
        if (text.contains("=") && text.contains(";")) score += 1;
        if (text.contains("()") || text.contains("[]") || text.contains("<>")) score += 1;
        if (text.contains("{") && text.contains("}")) score += 1;
        if (text.contains("function ") || text.contains("const ") || text.contains("var ")
                || text.contains("let ") || text.contains("def ") || text.contains("func ")) {
            score += 2;
        }
        if (text.contains("//") || text.contains("/*") || text.contains("*/") || text.contains("#")) {
            score += 1;
        }

        return score >= 3;
    }

    private static String extractStyle(XWPFParagraph para) {
        try {
            java.util.Map<String, Object> style = new java.util.LinkedHashMap<>();

            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr ppr = para.getCTP().getPPr();
            if (ppr != null) {
                if (ppr.getJc() != null) {
                    style.put("alignment", ppr.getJc().getVal().toString().toLowerCase());
                }
                if (ppr.getSpacing() != null) {
                    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSpacing spacing = ppr.getSpacing();
                    if (spacing.getLine() != null) {
                        style.put("lineSpacing", Integer.parseInt(spacing.getLine().toString()));
                    }
                    if (spacing.getBefore() != null) {
                        style.put("spaceBefore", Integer.parseInt(spacing.getBefore().toString()));
                    }
                    if (spacing.getAfter() != null) {
                        style.put("spaceAfter", Integer.parseInt(spacing.getAfter().toString()));
                    }
                }
                if (ppr.getInd() != null) {
                    org.openxmlformats.schemas.wordprocessingml.x2006.main.CTInd ind = ppr.getInd();
                    if (ind.getFirstLine() != null) {
                        style.put("firstLineIndent", Integer.parseInt(ind.getFirstLine().toString()));
                    }
                    if (ind.getFirstLineChars() != null) {
                        style.put("firstLineChars", Integer.parseInt(ind.getFirstLineChars().toString()));
                    }
                    if (ind.getLeft() != null) {
                        style.put("leftIndent", Integer.parseInt(ind.getLeft().toString()));
                    }
                }
            }

            if (!para.getRuns().isEmpty()) {
                XWPFRun firstRun = para.getRuns().get(0);
                if (firstRun.getFontSize() > 0) {
                    style.put("fontSize", firstRun.getFontSize());
                }
                if (firstRun.getFontFamily() != null) {
                    style.put("fontFamily", firstRun.getFontFamily());
                }
                if (firstRun.isBold()) {
                    style.put("bold", true);
                }
                if (firstRun.isItalic()) {
                    style.put("italic", true);
                }
                if (firstRun.getColor() != null && !firstRun.getColor().equals("000000")) {
                    style.put("color", firstRun.getColor());
                }
            }

            String styleName = para.getStyle();
            if (styleName != null) {
                style.put("styleName", styleName);
                if (styleName.contains("Heading1") || styleName.equals("1") || styleName.equals("Heading1")) {
                    style.put("headingLevel", 1);
                } else if (styleName.contains("Heading2") || styleName.equals("2")) {
                    style.put("headingLevel", 2);
                } else if (styleName.contains("Heading3") || styleName.equals("3")) {
                    style.put("headingLevel", 3);
                }
            }

            if (style.isEmpty()) return null;
            return MAPPER.writeValueAsString(style);
        } catch (Exception e) {
            return null;
        }
    }
}
