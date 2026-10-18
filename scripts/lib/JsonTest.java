package lib;

import static lib.Assert.assertEquals;
import static lib.Assert.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class JsonTest {

    @Test
    void valuesRoundTrip() {
        Object value = Json.parse("{\"a\": [1, -2.5, true, false, null], \"b\": {\"c\": \"x\\\"\\n\\u00e9\"}, \"d\": {}}");
        var map = Json.object(value);
        assertEquals(List.of(1L, -2.5, true, false), Json.array(map.get("a")).subList(0, 4));
        assertEquals("x\"\né", Json.object(map.get("b")).get("c"));
        assertEquals(Json.write(value), Json.write(Json.parse(Json.write(value))));
    }

    @Test
    void writesAsPythonDoes() {
        var map = new LinkedHashMap<String, Object>();
        map.put("n", 1);
        map.put("f", 2.0);
        map.put("list", List.of("é", 3L));
        map.put("empty", List.of());
        map.put("none", null);
        assertEquals("{\"n\": 1, \"f\": 2.0, \"list\": [\"\\u00e9\", 3], \"empty\": [], \"none\": null}", Json.write(map));
        assertEquals("{\n  \"a\": [\n    1,\n    {}\n  ]\n}", Json.pretty(Map.of("a", List.of(1, Map.of()))));
    }

    @Test
    void incompleteTextIsRefused() {
        for (String text : List.of("", "{", "{\"url\":", "[1,", "{\"a\": 1} x", "{broken}")) {
            assertThrows(Json.ParseException.class, () -> Json.parse(text));
        }
    }
}
