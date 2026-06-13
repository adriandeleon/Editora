package com.example.editora.jsontools;

import java.io.StringReader;
import java.io.StringWriter;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** XML pretty-printer using the JDK's {@code java.xml} (secure-processing on; no external entities). */
final class XmlFormat {

    private XmlFormat() {
    }

    static String pretty(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        trySetFeature(dbf, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(dbf, "http://xml.org/sax/features/external-parameter-entities", false);
        Document doc = dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

        // Strip whitespace-only text nodes so the transformer can re-indent cleanly.
        NodeList blanks = (NodeList) XPathFactory.newInstance().newXPath()
                .evaluate("//text()[normalize-space(.)='']", doc, XPathConstants.NODESET);
        for (int i = 0; i < blanks.getLength(); i++) {
            Node n = blanks.item(i);
            n.getParentNode().removeChild(n);
        }

        TransformerFactory tff = TransformerFactory.newInstance();
        tff.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tff.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer t = tff.newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter sw = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString().strip();
    }

    private static void trySetFeature(DocumentBuilderFactory dbf, String feature, boolean value) {
        try {
            dbf.setFeature(feature, value);
        } catch (Exception ignored) {
            // best-effort hardening; not all parsers support every feature
        }
    }
}
