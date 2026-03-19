package org.adriandeleon.editora.languages;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TextMatePlistParser {
    private TextMatePlistParser() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(InputStream inputStream) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setIgnoringComments(true);
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(inputStream);
            Element root = document.getDocumentElement();
            Element valueElement = firstChildElement(root);
            Object value = parseValue(valueElement);
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            throw new IllegalArgumentException("TextMate plist root must be a dict");
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to parse TextMate plist", exception);
        }
    }

    private static Object parseValue(Element element) {
        return switch (element.getTagName()) {
            case "dict" -> parseDict(element);
            case "array" -> parseArray(element);
            case "string", "key" -> element.getTextContent();
            case "integer" -> Integer.parseInt(element.getTextContent().trim());
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("Unsupported plist value: " + element.getTagName());
        };
    }

    private static Map<String, Object> parseDict(Element element) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Element> children = childElements(element);
        for (int index = 0; index < children.size(); index += 2) {
            Element keyElement = children.get(index);
            if (!"key".equals(keyElement.getTagName())) {
                throw new IllegalArgumentException("Expected plist key but found " + keyElement.getTagName());
            }
            if (index + 1 >= children.size()) {
                throw new IllegalArgumentException("Missing plist value for key " + keyElement.getTextContent());
            }
            result.put(keyElement.getTextContent(), parseValue(children.get(index + 1)));
        }
        return result;
    }

    private static List<Object> parseArray(Element element) {
        List<Object> result = new ArrayList<>();
        for (Element child : childElements(element)) {
            result.add(parseValue(child));
        }
        return result;
    }

    private static Element firstChildElement(Element element) {
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement) {
                return childElement;
            }
        }
        throw new IllegalArgumentException("Expected child element for plist root");
    }

    private static List<Element> childElements(Element element) {
        List<Element> result = new ArrayList<>();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childElement) {
                result.add(childElement);
            }
        }
        return result;
    }
}

