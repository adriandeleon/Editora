// A green Spock specification of the shelf's classes: each `def "…"()` feature is a test with its own gutter ▶.
import spock.lang.Specification

class ShelfSpec extends Specification {

    def "a stacked discount takes 10% and then 5%"() {
        given:
        def item = new Item('A-1', 'Pencil', 12, 1.00)

        expect:
        Pricing.apply(Pricing.discountFor(item), item.price) == 0.855 // break here, then Debug Test at Caret: step into apply
    }

    def "the discount follows quantity and price"() {
        expect:
        Pricing.apply(Pricing.discountFor(new Item('Z-1', 'Test', quantity, price)), price) == expected

        where:
        quantity | price || expected
        1        | 8.90  || 7.565
        4        | 1.20  || 1.20
    }

    def "restock counts every worker"() {
        expect:
        Restock.run(3) == 480
    }
}
