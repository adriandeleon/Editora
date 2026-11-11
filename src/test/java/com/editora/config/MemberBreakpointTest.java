package com.editora.config;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Method breakpoints and field watchpoints as stored, named and typed. */
class MemberBreakpointTest {

    @Test
    void aTypedMemberIsReadInEachUsualSpelling() {
        for (String text : List.of(
                "com.example.Order#total",
                " com.example.Order::total ",
                "com.example.Order.total",
                "com.example.Order#total()")) {
            MemberBreakpoint.Named n = MemberBreakpoint.parse(text);
            assertEquals(new MemberBreakpoint.Named("com.example.Order", "total"), n, text);
        }
        assertEquals(
                new MemberBreakpoint.Named("a.Outer$Inner", "<init>"), MemberBreakpoint.parse("a.Outer$Inner#<init>"));
        assertEquals(new MemberBreakpoint.Named("Order", "total"), MemberBreakpoint.parse("Order#total"));
        for (String bad : List.of("", "total", "#total", "a.Order#", "a.Order#to tal", "a..Order#x", "1a.B#c")) {
            assertNull(MemberBreakpoint.parse(bad), bad);
        }
        assertNull(MemberBreakpoint.parse(null));
    }

    @Test
    void namesForTheAdapterAndForTheList() {
        MemberBreakpoint m = MemberBreakpoint.method("com.example.Outer$Inner", "run");
        assertEquals("com.example.Outer$Inner#run", m.qualifiedName());
        assertEquals("Outer.Inner.run()", m.shortName());
        assertEquals(
                "Order.count", MemberBreakpoint.field("Order", "count", "write").shortName());
    }

    @Test
    void aFieldWatchesAssignmentsUnlessToldOtherwiseAndAMethodHasNoAccess() {
        assertEquals("write", MemberBreakpoint.field("a.B", "c", null).access());
        assertEquals("write", MemberBreakpoint.field("a.B", "c", "nonsense").access());
        assertEquals("read", MemberBreakpoint.field("a.B", "c", "read").access());
        assertEquals(
                "read_write", MemberBreakpoint.field("a.B", "c", "readWrite").accessKey());
        assertEquals("", new MemberBreakpoint(MemberBreakpoint.Kind.METHOD, "a.B", "c", "read", "", "", true).access());
    }

    @Test
    void theSameMemberIsTheSameBreakpointWhateverItsSettings() {
        MemberBreakpoint a = MemberBreakpoint.field("a.B", "c", "write");
        assertTrue(a.sameMember(a.withAccess("read").withCondition("x > 1").withEnabled(false)));
        assertFalse(a.sameMember(MemberBreakpoint.method("a.B", "c")), "a method of the same name is another");
        assertFalse(a.sameMember(MemberBreakpoint.field("a.D", "c", "write")));
    }

    @Test
    void theStoreKeepsThemPerProjectAndAnOlderFileHasNone() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BreakpointStore store = new BreakpointStore();
        store.members("p").add(MemberBreakpoint.method("a.Order", "total").withCondition("n > 2"));
        store.members("p")
                .add(MemberBreakpoint.field("a.Order", "count", "readWrite").withEnabled(false));
        BreakpointStore back = mapper.readValue(mapper.writeValueAsString(store), BreakpointStore.class);
        assertEquals(store.members("p"), back.members("p"));
        assertTrue(back.members("other").isEmpty());

        BreakpointStore v3 = mapper.readValue("{\"schemaVersion\":3,\"byProject\":{}}", BreakpointStore.class);
        assertTrue(v3.getMemberBreakpoints().isEmpty());
        assertTrue(BreakpointStore.SCHEMA_VERSION >= 4, "member breakpoints came with schema 4");
    }

    @Test
    void aMemberBreakpointKeepsItsGroup() throws Exception {
        MemberBreakpoint m = MemberBreakpoint.method("p.A", "run").withGroup(" Startup ");
        assertEquals("Startup", m.group());
        assertEquals(
                "Startup",
                m.withEnabled(false).withCondition("x").withHitCondition("2").group());
        assertEquals(
                "Startup",
                MemberBreakpoint.field("p.A", "f", "read")
                        .withGroup("Startup")
                        .withAccess("write")
                        .group());
        ObjectMapper json = new ObjectMapper();
        assertEquals(m, json.readValue(json.writeValueAsString(m), MemberBreakpoint.class));
        assertEquals(
                "",
                json.readValue("{\"kind\":\"METHOD\",\"className\":\"p.A\",\"member\":\"run\"}", MemberBreakpoint.class)
                        .group());
    }
}
