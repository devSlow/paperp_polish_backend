package com.paper.polish.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.StrokeStyle;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xslf.usermodel.*;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.*;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SVG to PPTX converter following ppt-master philosophy.
 *
 * Pipeline: AI generates SVG (absolute-coordinate vector graphics)
 * → this converter maps each SVG element to native DrawingML shapes via Apache POI XSLF.
 *
 * Result is fully editable in PowerPoint — real shapes, real text boxes, not images.
 *
 * Supported SVG elements:
 * - <rect> → XSLF rectangle/rounded-rectangle
 * - <ellipse> / <circle> → XSLF ellipse
 * - <line> → XSLF connector shape
 * - <polygon> → XSLF freeform path
 * - <path> → XSLF freeform (M/L/Z/H/V commands)
 * - <text> → XSLF text box (with <tspan> support)
 * - <g> → group (nested transforms)
 * - transform="translate|rotate|scale"
 *
 * SVG restrictions (enforced by AI prompt):
 * - No <style>, no class, no mask, no filter, no animate, no script
 * - Inline styles only (fill="...", stroke="...", font-size="...")
 * - HEX colors only, transparency via fill-opacity/stroke-opacity
 * - viewBox must match canvas dimensions
 */
@Slf4j
public class SvgPptxConverter {

    private static final double SVG_WIDTH = 1280.0;
    private static final double SVG_HEIGHT = 720.0;
    private static final double FONT_SIZE_SCALE = 1.0;
    private static final double TEXT_PADDING = 12.0;
    private static final double CHAR_WIDTH_FACTOR = 0.6;
    private static final double LINE_HEIGHT_FACTOR = 1.5;

    public static byte[] convertToPptx(List<String> svgSlides, String title) throws Exception {
        log.info("===== 开始转换 SVG -> PPTX，总页数: {} =====", svgSlides.size());
        XMLSlideShow ppt = new XMLSlideShow();
        ppt.setPageSize(new java.awt.Dimension((int) SVG_WIDTH, (int) SVG_HEIGHT));

        for (int i = 0; i < svgSlides.size(); i++) {
            String svg = svgSlides.get(i);
            log.info("==== 处理第 {} 页 SVG，长度: {} ====", i + 1, svg.length());
            XSLFSlide slide = ppt.createSlide();
            convertSvgToSlide(svg, slide);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ppt.write(out);
        ppt.close();
        return out.toByteArray();
    }

    private static void convertSvgToSlide(String svgContent, XSLFSlide slide) throws Exception {
        log.info("SVG原始内容前200字: {}", svgContent.substring(0, Math.min(200, svgContent.length())));
        
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setValidating(false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        org.w3c.dom.Document doc;

        // 清理 XML 声明和 DOCTYPE
        String cleaned = svgContent.replaceAll("<\\?xml[^?]*\\?>", "").trim();
        cleaned = cleaned.replaceAll("<!DOCTYPE[^>]*>", "");
        cleaned = cleaned.replaceAll("<!\\[CDATA\\[[\\s\\S]*?]]>", ""); // 清理 CDATA
        cleaned = cleaned.replaceAll("<!\\[endif\\]-->", ""); // 清理条件注释
        
        // 移除所有 <! 开头的非法标签
        cleaned = cleaned.replaceAll("<![^>]+>", "");
        
        // 移除 sanitizeXmlTextContent，因为它会转义 < 字符导致 SVG 解析失败
        // cleaned = sanitizeXmlTextContent(cleaned);
        
        // 确保以 <svg 开头
        int svgStart = cleaned.indexOf("<svg");
        if (svgStart > 0) {
            cleaned = cleaned.substring(svgStart);
        }
        cleaned = cleaned.trim();
        
        log.info("SVG清理后内容前200字: {}", cleaned.substring(0, Math.min(200, cleaned.length())));
        
        // 最终验证：确保 SVG 完整
        if (!cleaned.contains("</svg>")) {
            throw new IllegalArgumentException("SVG 不完整：缺少 </svg> 闭合标签");
        }
        
        try (InputStream is = new ByteArrayInputStream(cleaned.getBytes(StandardCharsets.UTF_8))) {
            doc = builder.parse(is);
        } catch (Exception e) {
            // 尝试更宽松的修复
            String repaired = attemptSvgRepair(cleaned);
            if (repaired != null) {
                try (InputStream is2 = new ByteArrayInputStream(repaired.getBytes(StandardCharsets.UTF_8))) {
                    doc = builder.parse(is2);
                } catch (Exception e2) {
                    throw new Exception("SVG 解析失败: " + e.getMessage(), e);
                }
            } else {
                throw new Exception("SVG 解析失败: " + e.getMessage(), e);
            }
        }

        NodeList children = doc.getDocumentElement().getChildNodes();
        TransformState globalTransform = new TransformState();

        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element el = (Element) node;
            String tag = el.getLocalName();
            if (tag == null) tag = el.getTagName();
            tag = tag.toLowerCase();
            if ("defs".equals(tag) || "title".equals(tag) || "desc".equals(tag)) continue;
            processElement(el, slide, globalTransform);
        }
    }

    private static void processElement(Element el, XSLFSlide slide, TransformState parentTransform) {
        String tag = el.getTagName().toLowerCase();
        String local = el.getLocalName();
        if (local != null) tag = local.toLowerCase();

        String transform = el.getAttribute("transform");
        TransformState localTransform = parentTransform.derive(transform);

        switch (tag) {
            case "rect":
                convertRect(el, slide, localTransform);
                break;
            case "ellipse":
                convertEllipse(el, slide, localTransform);
                break;
            case "circle":
                convertCircle(el, slide, localTransform);
                break;
            case "line":
                convertLine(el, slide, localTransform);
                break;
            case "polygon":
                convertPolygon(el, slide, localTransform);
                break;
            case "path":
                convertPath(el, slide, localTransform);
                break;
            case "text":
                convertText(el, slide, localTransform);
                break;
            case "g":
                convertGroup(el, slide, localTransform);
                break;
            default:
                break;
        }
    }

    private static void convertRect(Element el, XSLFSlide slide, TransformState ts) {
        String widthAttr = el.getAttribute("width");
        String heightAttr = el.getAttribute("height");
        if (widthAttr.isEmpty() || heightAttr.isEmpty()) return;

        double x = parseDouble(el.getAttribute("x"), 0);
        double y = parseDouble(el.getAttribute("y"), 0);
        double w = parseDouble(widthAttr, 0);
        double h = parseDouble(heightAttr, 0);
        if (w < 1 || h < 1) return;

        double[] p = ts.transform(x, y);
        double[] s = ts.transformScale(w, h);

        XSLFAutoShape shape = slide.createAutoShape();
        boolean isRounded = !el.getAttribute("rx").isEmpty() || !el.getAttribute("ry").isEmpty();
        shape.setShapeType(isRounded ? ShapeType.ROUND_RECT : ShapeType.RECT);
        shape.setAnchor(new Rectangle2D.Double(p[0], p[1], s[0], s[1]));

        applyFill(shape, el.getAttribute("fill"), el.getAttribute("fill-opacity"));
        applyStroke(shape, el);
    }

    private static void convertEllipse(Element el, XSLFSlide slide, TransformState ts) {
        String rxAttr = el.getAttribute("rx");
        String ryAttr = el.getAttribute("ry");
        if (rxAttr.isEmpty() || ryAttr.isEmpty()) return;

        double cx = parseDouble(el.getAttribute("cx"), 0);
        double cy = parseDouble(el.getAttribute("cy"), 0);
        double rx = parseDouble(rxAttr, 0);
        double ry = parseDouble(ryAttr, 0);

        double x = cx - rx;
        double y = cy - ry;
        double w = rx * 2;
        double h = ry * 2;

        double[] p = ts.transform(x, y);
        double[] s = ts.transformScale(w, h);

        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.ELLIPSE);
        shape.setAnchor(new Rectangle2D.Double(p[0], p[1], s[0], s[1]));

        applyFill(shape, el.getAttribute("fill"), el.getAttribute("fill-opacity"));
        applyStroke(shape, el);
    }

    private static void convertCircle(Element el, XSLFSlide slide, TransformState ts) {
        String rAttr = el.getAttribute("r");
        if (rAttr.isEmpty()) return;

        double cx = parseDouble(el.getAttribute("cx"), 0);
        double cy = parseDouble(el.getAttribute("cy"), 0);
        double r = parseDouble(rAttr, 0);

        double x = cx - r;
        double y = cy - r;
        double w = r * 2;
        double h = r * 2;

        double[] p = ts.transform(x, y);
        double[] s = ts.transformScale(w, h);

        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.ELLIPSE);
        shape.setAnchor(new Rectangle2D.Double(p[0], p[1], s[0], s[1]));

        applyFill(shape, el.getAttribute("fill"), el.getAttribute("fill-opacity"));
        applyStroke(shape, el);
    }

    private static void convertLine(Element el, XSLFSlide slide, TransformState ts) {
        double x1 = parseDouble(el.getAttribute("x1"), 0);
        double y1 = parseDouble(el.getAttribute("y1"), 0);
        double x2 = parseDouble(el.getAttribute("x2"), 0);
        double y2 = parseDouble(el.getAttribute("y2"), 0);

        double[] p1 = ts.transform(x1, y1);
        double[] p2 = ts.transform(x2, y2);

        double minX = Math.min(p1[0], p2[0]);
        double minY = Math.min(p1[1], p2[1]);
        double w = Math.abs(p2[0] - p1[0]);
        double h = Math.abs(p2[1] - p1[1]);

        double strokeWidth = parseDouble(el.getAttribute("stroke-width"), 1.0);
        if (w < 1 && h < 1) return;

        if (w < 1) {
            XSLFAutoShape shape = slide.createAutoShape();
            shape.setShapeType(ShapeType.RECT);
            shape.setAnchor(new Rectangle2D.Double(minX - strokeWidth / 2, minY, strokeWidth, Math.max(h, 1)));
            applyFill(shape, el.getAttribute("stroke"), null);
        } else if (h < 1) {
            XSLFAutoShape shape = slide.createAutoShape();
            shape.setShapeType(ShapeType.RECT);
            shape.setAnchor(new Rectangle2D.Double(minX, minY - strokeWidth / 2, Math.max(w, 1), strokeWidth));
            applyFill(shape, el.getAttribute("stroke"), null);
        } else {
            GeneralPath path = new GeneralPath();
            path.moveTo((float) p1[0], (float) p1[1]);
            path.lineTo((float) p2[0], (float) p2[1]);
            XSLFFreeformShape shape = slide.createFreeform();
            shape.setPath(path);
            shape.setAnchor(new Rectangle2D.Double(0, 0, SVG_WIDTH, SVG_HEIGHT));
            shape.setLineColor(parseColor(el.getAttribute("stroke")));
            shape.setLineWidth(strokeWidth);
            String dasharray = el.getAttribute("stroke-dasharray");
            if (!dasharray.isEmpty()) {
                shape.setLineDash(StrokeStyle.LineDash.DASH);
            }
        }
    }

    private static void convertPolygon(Element el, XSLFSlide slide, TransformState ts) {
        String points = el.getAttribute("points");
        if (points.isEmpty()) return;

        String[] tokens = points.trim().split("[\\s,]+");
        GeneralPath path = new GeneralPath();
        boolean first = true;

        for (int i = 0; i + 1 < tokens.length; i += 2) {
            double x = parseDouble(tokens[i], 0);
            double y = parseDouble(tokens[i + 1], 0);
            double[] p = ts.transform(x, y);
            if (first) {
                path.moveTo((float) p[0], (float) p[1]);
                first = false;
            } else {
                path.lineTo((float) p[0], (float) p[1]);
            }
        }
        path.closePath();

        XSLFFreeformShape shape = slide.createFreeform();
        shape.setPath(path);
        shape.setAnchor(new Rectangle2D.Double(0, 0, SVG_WIDTH, SVG_HEIGHT));

        applyFillFreeform(shape, el.getAttribute("fill"), el.getAttribute("fill-opacity"));
        applyStrokeFreeform(shape, el);
    }

    private static void convertPath(Element el, XSLFSlide slide, TransformState ts) {
        String d = el.getAttribute("d");
        if (d.isEmpty()) return;

        try {
            GeneralPath path = parseSvgPath(d, ts);
            if (path != null) {
                XSLFFreeformShape shape = slide.createFreeform();
                shape.setPath(path);
                shape.setAnchor(new Rectangle2D.Double(0, 0, SVG_WIDTH, SVG_HEIGHT));
                applyFillFreeform(shape, el.getAttribute("fill"), el.getAttribute("fill-opacity"));
                applyStrokeFreeform(shape, el);
            }
        } catch (Exception e) {
            // Path parsing failed, skip
        }
    }

    private static GeneralPath parseSvgPath(String d, TransformState ts) {
        GeneralPath path = new GeneralPath();
        String[] tokens = d.split("(?=[MmLlHhVvZz])");
        double currentX = 0, currentY = 0;
        double startX = 0, startY = 0;

        for (String token : tokens) {
            if (token.isEmpty()) continue;
            char cmd = token.charAt(0);
            String[] nums = token.substring(1).trim().split("[,\\s]+");
            List<Double> values = new ArrayList<>();
            for (String n : nums) {
                if (!n.isEmpty()) values.add(parseDouble(n, 0));
            }

            switch (cmd) {
                case 'M':
                    if (values.size() >= 2) {
                        double[] p = ts.transform(values.get(0), values.get(1));
                        path.moveTo((float) p[0], (float) p[1]);
                        currentX = values.get(0);
                        currentY = values.get(1);
                        startX = currentX;
                        startY = currentY;
                    }
                    break;
                case 'm':
                    if (values.size() >= 2) {
                        currentX += values.get(0);
                        currentY += values.get(1);
                        double[] p = ts.transform(currentX, currentY);
                        path.moveTo((float) p[0], (float) p[1]);
                        startX = currentX;
                        startY = currentY;
                    }
                    break;
                case 'L':
                    if (values.size() >= 2) {
                        double[] p = ts.transform(values.get(0), values.get(1));
                        path.lineTo((float) p[0], (float) p[1]);
                        currentX = values.get(0);
                        currentY = values.get(1);
                    }
                    break;
                case 'l':
                    if (values.size() >= 2) {
                        currentX += values.get(0);
                        currentY += values.get(1);
                        double[] p = ts.transform(currentX, currentY);
                        path.lineTo((float) p[0], (float) p[1]);
                    }
                    break;
                case 'H':
                    if (!values.isEmpty()) {
                        currentX = values.get(0);
                        double[] p = ts.transform(currentX, currentY);
                        path.lineTo((float) p[0], (float) p[1]);
                    }
                    break;
                case 'V':
                    if (!values.isEmpty()) {
                        currentY = values.get(0);
                        double[] p = ts.transform(currentX, currentY);
                        path.lineTo((float) p[0], (float) p[1]);
                    }
                    break;
                case 'Z':
                case 'z':
                    double[] pe = ts.transform(startX, startY);
                    path.lineTo((float) pe[0], (float) pe[1]);
                    currentX = startX;
                    currentY = startY;
                    break;
            }
        }
        return path;
    }

    private static void convertText(Element el, XSLFSlide slide, TransformState ts) {
        String xAttr = el.getAttribute("x");
        String yAttr = el.getAttribute("y");
        if (xAttr.isEmpty() || yAttr.isEmpty()) return;

        double x = parseDouble(xAttr, 0);
        double y = parseDouble(yAttr, 0);
        double[] p = ts.transform(x, y);

        String fontSize = el.getAttribute("font-size");
        String fontFamily = el.getAttribute("font-family");
        String fontWeight = el.getAttribute("font-weight");
        String fill = el.getAttribute("fill");
        String textAnchor = el.getAttribute("text-anchor");
        String opacity = el.getAttribute("opacity");
        String textDecoration = el.getAttribute("text-decoration");
        String fontStyle = el.getAttribute("font-style");

        double scaledFontSize = (fontSize.isEmpty() ? 18 : parseDouble(fontSize, 18)) * FONT_SIZE_SCALE;

        XSLFTextBox textBox = slide.createTextBox();

        NodeList children = el.getChildNodes();
        boolean hasTspans = false;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String childTag = ((Element) child).getLocalName();
                if (childTag == null) childTag = child.getNodeName();
                if ("tspan".equalsIgnoreCase(childTag)) {
                    hasTspans = true;
                    break;
                }
            }
        }

        StringBuilder allText = new StringBuilder();
        if (hasTspans) {
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.TEXT_NODE) {
                    allText.append(child.getTextContent());
                } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                    allText.append(extractTextContent((Element) child));
                }
            }
        } else {
            allText.append(extractTextContent(el));
        }

        String text = allText.toString().trim();
        if (text.isEmpty()) return;

        // 计算文本宽度：中文字符约占1个fontSize宽度，英文约占0.6
        double textWidth = 0;
        for (char c : text.toCharArray()) {
            if (c > 127) {
                textWidth += scaledFontSize; // 中文字符
            } else {
                textWidth += scaledFontSize * CHAR_WIDTH_FACTOR; // 英文字符
            }
        }
        textWidth = Math.max(textWidth + TEXT_PADDING * 3, 80);
        
        // 计算文本高度：单行高度，加上上下padding
        double lineHeight = scaledFontSize * 1.3; // 单行高度
        double textHeight = lineHeight + TEXT_PADDING * 2;
        
        double anchorX;
        double anchorY;
        
        // SVG y是baseline位置，POI anchor是top edge
        // 对于中文文字，baseline到top的距离约为字体大小的0.65倍
        double baselineToTop = scaledFontSize * 0.65;
        
        if (textAnchor != null && ("middle".equals(textAnchor) || "center".equals(textAnchor))) {
            // 居中对齐：文本框居中放置，POI内部处理对齐
            anchorX = p[0] - textWidth / 2;
        } else if ("end".equals(textAnchor) || "right".equals(textAnchor)) {
            // 右对齐
            anchorX = p[0] - textWidth;
        } else {
            // 左对齐（默认）
            anchorX = p[0];
        }
        
        anchorY = p[1] - baselineToTop;

        // 边界检查：防止文本框超出画布
        if (anchorX < 0) anchorX = 5;
        if (anchorY < 0) anchorY = 5;
        if (anchorX + textWidth > SVG_WIDTH) {
            textWidth = SVG_WIDTH - anchorX - 5;
        }
        if (anchorY + textHeight > SVG_HEIGHT) {
            textHeight = SVG_HEIGHT - anchorY - 5;
        }
        
        textBox.setAnchor(new Rectangle2D.Double(anchorX, anchorY, textWidth, textHeight));
        
        // 设置文本框属性
        textBox.setWordWrap(true);
        textBox.setTextAutofit(XSLFTextShape.TextAutofit.NONE);

        XSLFTextParagraph para = textBox.addNewTextParagraph();
        para.setTextAlign(mapTextAlign(textAnchor));

        if (hasTspans) {
            XSLFTextParagraph currentPara = para;
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    Element childEl = (Element) child;
                    String childTag = childEl.getLocalName();
                    if (childTag == null) childTag = childEl.getNodeName();
                    if ("tspan".equalsIgnoreCase(childTag)) {
                        String dy = childEl.getAttribute("dy");
                        String tX = childEl.getAttribute("x");

                        if ((!dy.isEmpty() || !tX.isEmpty()) && currentPara.getTextRuns().size() > 0) {
                            currentPara = textBox.addNewTextParagraph();
                        }

                        XSLFTextRun run = currentPara.addNewTextRun();
                        run.setText(extractTextContent(childEl));

                        String tFontSize = childEl.getAttribute("font-size");
                        String tFontFamily = childEl.getAttribute("font-family");
                        String tFontWeight = childEl.getAttribute("font-weight");
                        String tFill = childEl.getAttribute("fill");
                        String tTextDecoration = childEl.getAttribute("text-decoration");
                        String tFontStyle = childEl.getAttribute("font-style");

                        applyTextRunStyle(run,
                                tFontSize.isEmpty() ? fontSize : tFontSize,
                                tFontFamily.isEmpty() ? fontFamily : tFontFamily,
                                tFontWeight.isEmpty() ? fontWeight : tFontWeight,
                                tFill.isEmpty() ? fill : tFill,
                                tTextDecoration.isEmpty() ? textDecoration : tTextDecoration,
                                tFontStyle.isEmpty() ? fontStyle : tFontStyle,
                                opacity);
                    }
                } else if (child.getNodeType() == Node.TEXT_NODE) {
                    String textContent = child.getTextContent();
                    if (textContent != null && !textContent.trim().isEmpty()) {
                        XSLFTextRun run = currentPara.addNewTextRun();
                        run.setText(textContent);
                        applyTextRunStyle(run, fontSize, fontFamily, fontWeight, fill, textDecoration, fontStyle, opacity);
                    }
                }
            }
        } else {
            XSLFTextRun run = para.addNewTextRun();
            run.setText(text);
            applyTextRunStyle(run, fontSize, fontFamily, fontWeight, fill, textDecoration, fontStyle, opacity);
        }
    }

    private static void applyTextRunStyle(XSLFTextRun run, String fontSize, String fontFamily,
                                           String fontWeight, String fill, String textDecoration,
                                           String fontStyle, String opacity) {
        if (!fontSize.isEmpty()) {
            run.setFontSize(parseDouble(fontSize, 18) * FONT_SIZE_SCALE);
        }
        if (!fontFamily.isEmpty()) {
            run.setFontFamily(fontFamily.replaceAll("['\"]", "").split(",")[0].trim());
        }
        if ("bold".equalsIgnoreCase(fontWeight) || "700".equals(fontWeight)
                || "800".equals(fontWeight) || "900".equals(fontWeight)) {
            run.setBold(true);
        }
        if ("italic".equalsIgnoreCase(fontStyle)) {
            run.setItalic(true);
        }
        if (fill != null && !fill.isEmpty() && !fill.equalsIgnoreCase("none")) {
            run.setFontColor(parseColor(fill));
        }
        if ("underline".equalsIgnoreCase(textDecoration)) {
            run.setUnderlined(true);
        }
    }

    private static void convertGroup(Element el, XSLFSlide slide, TransformState ts) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                processElement((Element) child, slide, ts);
            }
        }
    }

    private static String extractTextContent(Element el) {
        StringBuilder sb = new StringBuilder();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                sb.append(child.getTextContent());
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                sb.append(extractTextContent((Element) child));
            }
        }
        return sb.toString();
    }

    private static void applyFill(XSLFAutoShape shape, String fill, String fillOpacity) {
        if (fill == null || fill.isEmpty() || "none".equalsIgnoreCase(fill)) return;
        Color color = parseColor(fill);
        if (fillOpacity != null && !fillOpacity.isEmpty()) {
            double o = Math.max(0, Math.min(1, parseDouble(fillOpacity, 1.0)));
            color = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (o * 255));
        }
        shape.setFillColor(color);
    }

    private static void applyFillFreeform(XSLFFreeformShape shape, String fill, String fillOpacity) {
        if (fill == null || fill.isEmpty() || "none".equalsIgnoreCase(fill)) return;
        Color color = parseColor(fill);
        if (fillOpacity != null && !fillOpacity.isEmpty()) {
            double o = Math.max(0, Math.min(1, parseDouble(fillOpacity, 1.0)));
            color = new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (o * 255));
        }
        shape.setFillColor(color);
    }

    private static void applyStroke(XSLFAutoShape shape, Element el) {
        String stroke = el.getAttribute("stroke");
        if (stroke == null || stroke.isEmpty() || "none".equalsIgnoreCase(stroke)) return;
        shape.setLineColor(parseColor(stroke));
        String strokeWidth = el.getAttribute("stroke-width");
        if (!strokeWidth.isEmpty()) {
            shape.setLineWidth(parseDouble(strokeWidth, 1.0));
        }
        String dasharray = el.getAttribute("stroke-dasharray");
        if (!dasharray.isEmpty()) {
            shape.setLineDash(StrokeStyle.LineDash.DASH);
        }
    }

    private static void applyStrokeFreeform(XSLFFreeformShape shape, Element el) {
        String stroke = el.getAttribute("stroke");
        if (stroke == null || stroke.isEmpty() || "none".equalsIgnoreCase(stroke)) return;
        shape.setLineColor(parseColor(stroke));
        String strokeWidth = el.getAttribute("stroke-width");
        if (!strokeWidth.isEmpty()) {
            shape.setLineWidth(parseDouble(strokeWidth, 1.0));
        }
        String dasharray = el.getAttribute("stroke-dasharray");
        if (!dasharray.isEmpty()) {
            shape.setLineDash(StrokeStyle.LineDash.DASH);
        }
    }

    private static Color parseColor(String color) {
        if (color == null || color.isEmpty()) return Color.BLACK;
        color = color.trim();
        if (color.startsWith("#")) {
            color = color.substring(1);
            if (color.length() == 3) {
                color = "" + color.charAt(0) + color.charAt(0) + color.charAt(1) + color.charAt(1) + color.charAt(2) + color.charAt(2);
            }
            if (color.length() == 8) {
                int rgba = (int) Long.parseLong(color, 16);
                return new Color((rgba >> 24) & 0xFF, (rgba >> 16) & 0xFF, (rgba >> 8) & 0xFF, rgba & 0xFF);
            }
            if (color.length() == 6) {
                return new Color((int) Long.parseLong(color, 16));
            }
            return Color.BLACK;
        }
        switch (color.toLowerCase()) {
            case "red": return Color.RED;
            case "green": return Color.GREEN;
            case "blue": return Color.BLUE;
            case "black": return Color.BLACK;
            case "white": return Color.WHITE;
            case "gray": case "grey": return Color.GRAY;
            case "yellow": return Color.YELLOW;
            case "orange": return Color.ORANGE;
            case "pink": return Color.PINK;
            case "cyan": return Color.CYAN;
            default: return Color.BLACK;
        }
    }

    private static TextParagraph.TextAlign mapTextAlign(String textAnchor) {
        switch (textAnchor) {
            case "middle":
            case "center":
                return TextParagraph.TextAlign.CENTER;
            case "end":
            case "right":
                return TextParagraph.TextAlign.RIGHT;
            case "start":
            case "left":
            default:
                return TextParagraph.TextAlign.LEFT;
        }
    }

    private static double parseDouble(String value, double defaultValue) {
        if (value == null || value.isEmpty()) return defaultValue;
        try {
            return Double.parseDouble(value.trim().replaceAll("[a-zA-Z%]", ""));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String sanitizeXmlTextContent(String svg) {
        StringBuilder sb = new StringBuilder(svg.length() + 256);
        int i = 0;
        int len = svg.length();
        while (i < len) {
            char c = svg.charAt(i);
            if (c == '<') {
                int tagEnd = svg.indexOf('>', i);
                if (tagEnd != -1 && tagEnd > i) {
                    String tag = svg.substring(i, tagEnd + 1);
                    if (tag.startsWith("</") || tag.endsWith("/>") ||
                            tag.matches("^<[a-zA-Z][a-zA-Z0-9]*[\\s>].*") ||
                            tag.startsWith("<!") || tag.startsWith("<?")) {
                        sb.append(tag);
                        i = tagEnd + 1;
                        continue;
                    }
                }
                sb.append("&lt;");
                i++;
            } else if (c == '&') {
                int semi = svg.indexOf(';', i);
                if (semi != -1 && semi - i < 10) {
                    String entity = svg.substring(i, semi + 1);
                    if (entity.matches("^&(lt|gt|amp|quot|apos|#\\d+|#x[0-9a-fA-F]+);$")) {
                        sb.append(entity);
                        i = semi + 1;
                        continue;
                    }
                }
                sb.append("&amp;");
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 尝试修复损坏的 SVG
     * 处理常见的 XML 解析错误，如未闭合标签、属性值未加引号等
     */
    private static String attemptSvgRepair(String svg) {
        if (svg == null || svg.isEmpty()) return null;
        
        try {
            // 1. 确保 svg 标签闭合
            if (!svg.contains("</svg>")) {
                svg = svg + "</svg>";
            }
            
            // 2. 修复未加引号的属性值 (例如: fill=#fff -> fill="#fff")
            svg = svg.replaceAll("([a-zA-Z-]+)=([^\"'][^\\s>]*)", "$1=\"$2\"");
            
            // 3. 修复常见问题：自闭合标签没有正确关闭
            svg = svg.replaceAll("<([a-zA-Z][a-zA-Z0-9]*)\\s+([^>]*)/\\s*>", "<$1 $2 />");
            
            // 4. 统计并补全未闭合的标签
            java.util.Stack<String> tagStack = new java.util.Stack<>();
            java.util.regex.Pattern tagPattern = java.util.regex.Pattern.compile("<(/?)([a-zA-Z][a-zA-Z0-9]*)[^>]*(/?)>");
            java.util.regex.Matcher matcher = tagPattern.matcher(svg);
            
            while (matcher.find()) {
                boolean isClosing = !matcher.group(1).isEmpty();
                boolean isSelfClosing = !matcher.group(3).isEmpty();
                String tagName = matcher.group(2).toLowerCase();
                
                if (isSelfClosing) continue;
                if (tagName.equals("svg") && isClosing) continue; // 根标签在最后处理
                
                if (isClosing) {
                    if (!tagStack.isEmpty() && tagStack.peek().equals(tagName)) {
                        tagStack.pop();
                    }
                } else {
                    tagStack.push(tagName);
                }
            }
            
            // 补全未闭合的标签
            StringBuilder repaired = new StringBuilder(svg);
            while (!tagStack.isEmpty()) {
                String tag = tagStack.pop();
                repaired.append("</").append(tag).append(">");
            }
            
            return repaired.toString();
        } catch (Exception e) {
            return null;
        }
    }

    public static class TransformState {
        private final double tx, ty, scale, rotation;

        public TransformState() {
            this(0, 0, 1.0, 0);
        }

        public TransformState(double tx, double ty, double scale, double rotation) {
            this.tx = tx;
            this.ty = ty;
            this.scale = scale;
            this.rotation = rotation;
        }

        public TransformState derive(String transformAttr) {
            if (transformAttr == null || transformAttr.isEmpty()) return this;

            double newTx = tx, newTy = ty, newScale = scale, newRotation = rotation;

            if (transformAttr.contains("translate")) {
                String args = extractArgs(transformAttr, "translate");
                if (args != null) {
                    String[] parts = args.split(",");
                    newTx += parseDouble(parts[0], 0);
                    newTy += parts.length > 1 ? parseDouble(parts[1], 0) : 0;
                }
            }
            if (transformAttr.contains("scale")) {
                String args = extractArgs(transformAttr, "scale");
                if (args != null) {
                    String[] parts = args.split(",");
                    newScale *= parseDouble(parts[0], 1.0);
                }
            }
            if (transformAttr.contains("rotate")) {
                String args = extractArgs(transformAttr, "rotate");
                if (args != null) {
                    newRotation += parseDouble(args.split(",")[0], 0);
                }
            }

            return new TransformState(newTx, newTy, newScale, newRotation);
        }

        private String extractArgs(String transform, String func) {
            int start = transform.indexOf(func + "(");
            if (start == -1) return null;
            start += func.length() + 1;
            int end = transform.indexOf(")", start);
            if (end == -1) return null;
            return transform.substring(start, end);
        }

        public double[] transform(double x, double y) {
            if (rotation != 0) {
                double rad = Math.toRadians(rotation);
                double cos = Math.cos(rad);
                double sin = Math.sin(rad);
                double rotatedX = x * cos - y * sin;
                double rotatedY = x * sin + y * cos;
                return new double[]{
                        rotatedX * scale + tx,
                        rotatedY * scale + ty
                };
            }
            return new double[]{
                    x * scale + tx,
                    y * scale + ty
            };
        }

        public double[] transformScale(double w, double h) {
            return new double[]{w * scale, h * scale};
        }
    }
}
