package com.bydwatch.companion;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Guards the Indonesian translation against drift: every translatable string must exist in
 * {@code values-in}, keep the same format specifiers, and no translation may outlive its key.
 */
public class StringsLocaleParityTest {

    private static final Pattern FORMAT_SPECIFIER = Pattern.compile("%(?:\\d+\\$)?[a-zA-Z]");

    @Test
    public void everyTranslatableStringHasAnIndonesianTranslation() throws Exception {
        Map<String, String> english = strings(values("strings.xml"));
        Map<String, String> indonesian = strings(valuesIn("strings.xml"));

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : english.entrySet()) {
            if (!indonesian.containsKey(entry.getKey())) missing.add(entry.getKey());
        }
        assertTrue("Missing values-in translations: " + missing, missing.isEmpty());
    }

    @Test
    public void noIndonesianStringOutlivesItsEnglishKey() throws Exception {
        Map<String, String> english = strings(values("strings.xml"));
        Map<String, String> indonesian = strings(valuesIn("strings.xml"));

        List<String> stale = new ArrayList<>();
        for (String key : indonesian.keySet()) {
            if (!english.containsKey(key)) stale.add(key);
        }
        assertTrue("values-in has keys that values/ no longer defines: " + stale, stale.isEmpty());
    }

    @Test
    public void translationsKeepTheSameFormatSpecifiers() throws Exception {
        Map<String, String> english = strings(values("strings.xml"));
        Map<String, String> indonesian = strings(valuesIn("strings.xml"));

        Map<String, String> mismatched = new TreeMap<>();
        for (Map.Entry<String, String> entry : english.entrySet()) {
            String translated = indonesian.get(entry.getKey());
            if (translated == null) continue;
            List<String> expected = specifiers(entry.getValue());
            List<String> actual = specifiers(translated);
            if (!expected.equals(actual)) {
                mismatched.put(entry.getKey(), expected + " -> " + actual);
            }
        }
        assertTrue("Format specifiers changed in translation: " + mismatched, mismatched.isEmpty());
    }

    @Test
    public void pluralsMatchTheirEnglishQuantities() throws Exception {
        Map<String, Set<String>> english = plurals(values("strings.xml"));
        Map<String, Set<String>> indonesian = plurals(valuesIn("strings.xml"));

        Map<String, String> mismatched = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : english.entrySet()) {
            Set<String> translated = indonesian.get(entry.getKey());
            if (translated == null) {
                mismatched.put(entry.getKey(), "missing");
            } else if (!translated.equals(entry.getValue())) {
                mismatched.put(entry.getKey(), entry.getValue() + " -> " + translated);
            }
        }
        assertTrue("Plural quantities changed in translation: " + mismatched, mismatched.isEmpty());
    }

    private static Map<String, String> strings(File file) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element element : elements(file, "string")) {
            if ("false".equals(element.getAttribute("translatable"))) continue;
            out.put(element.getAttribute("name"), element.getTextContent());
        }
        return out;
    }

    private static Map<String, Set<String>> plurals(File file) throws Exception {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Element element : elements(file, "plurals")) {
            Set<String> quantities = new TreeSet<>();
            NodeList items = element.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Node item = items.item(i);
                if (item instanceof Element) {
                    quantities.add(((Element) item).getAttribute("quantity"));
                }
            }
            out.put(element.getAttribute("name"), quantities);
        }
        return out;
    }

    private static List<Element> elements(File file, String tag) throws Exception {
        if (!file.isFile()) {
            fail("Resource file not found: " + file.getAbsolutePath()
                    + " (tests expect the module directory as working directory)");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        Document document = factory.newDocumentBuilder().parse(file);
        NodeList nodes = document.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private static List<String> specifiers(String text) {
        List<String> out = new ArrayList<>();
        Matcher matcher = FORMAT_SPECIFIER.matcher(text);
        while (matcher.find()) out.add(matcher.group());
        return out;
    }

    private static File values(String name) {
        return resource("values", name);
    }

    private static File valuesIn(String name) {
        return resource("values-in", name);
    }

    private static File resource(String folder, String name) {
        File module = new File(".").getAbsoluteFile();
        File direct = new File(module, "src/main/res/" + folder + "/" + name);
        if (direct.isFile()) return direct;
        return new File(module, "app/src/main/res/" + folder + "/" + name);
    }
}
