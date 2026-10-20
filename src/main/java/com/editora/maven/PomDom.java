package com.editora.maven;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A DOM of a pom.xml whose elements remember the <em>line</em> they start on, so the pom summary preview can
 * take the reader to the element behind a row (Maven review C9). {@code DocumentBuilder} drops positions;
 * this builds the same tree through SAX and stores the parser's {@link Locator} line on each element as
 * user data ({@link #LINE_KEY}, 1-based). Same XXE hardening as {@link PomParser#parseDocument}: no DOCTYPE,
 * no external entities. Pure; called off the FX thread.
 */
final class PomDom {

    /** User-data key under which an {@link Element} carries its 1-based start line (an {@link Integer}). */
    static final String LINE_KEY = "com.editora.maven.line";

    private PomDom() {}

    /** The 1-based line an element starts on, or 0 when it was not built here. */
    static int lineOf(Element el) {
        Object line = el == null ? null : el.getUserData(LINE_KEY);
        return line instanceof Integer i ? i : 0;
    }

    /** The parse failure with the position SAX knew (0 when it did not). */
    static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        final int line;
        final int column;

        Failure(String message, int line, int column, Throwable cause) {
            super(message, cause);
            this.line = line;
            this.column = column;
        }
    }

    static Document parse(String xml) throws Failure {
        String text = xml != null && !xml.isEmpty() && xml.charAt(0) == '﻿' ? xml.substring(1) : xml;
        try {
            SAXParserFactory spf = SAXParserFactory.newInstance();
            spf.setNamespaceAware(false);
            spf.setValidating(false);
            spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            spf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            spf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            spf.setXIncludeAware(false);
            SAXParser parser = spf.newSAXParser();
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.newDocument();
            Builder handler = new Builder(doc);
            parser.parse(new InputSource(new StringReader(text == null ? "" : text)), handler);
            return doc;
        } catch (SAXParseException e) {
            throw new Failure(message(e), Math.max(0, e.getLineNumber()), Math.max(0, e.getColumnNumber()), e);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new Failure(message(e), 0, 0, e);
        }
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }

    /** SAX → DOM, recording each element's start line. Text is appended as it arrives (CDATA included). */
    private static final class Builder extends DefaultHandler {
        private final Document doc;
        private final Deque<Node> stack = new ArrayDeque<>();
        private Locator locator;

        Builder(Document doc) {
            this.doc = doc;
            stack.push(doc);
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            Element el = doc.createElement(qName);
            for (int i = 0; i < attributes.getLength(); i++) {
                el.setAttribute(attributes.getQName(i), attributes.getValue(i));
            }
            if (locator != null && locator.getLineNumber() > 0) {
                el.setUserData(LINE_KEY, locator.getLineNumber(), null);
            }
            stack.peek().appendChild(el);
            stack.push(el);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            stack.pop();
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            Node top = stack.peek();
            if (top instanceof Element el) {
                Node last = el.getLastChild();
                if (last != null && last.getNodeType() == Node.TEXT_NODE) {
                    ((org.w3c.dom.Text) last).appendData(new String(ch, start, length));
                } else {
                    el.appendChild(doc.createTextNode(new String(ch, start, length)));
                }
            }
        }
    }
}
