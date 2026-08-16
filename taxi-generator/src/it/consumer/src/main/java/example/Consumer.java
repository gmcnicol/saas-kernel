package example;

import example.bindings.customer.crm.Customer;
import example.bindings.customer.crm.CustomerId;
import example.bindings.customer.crm.Status;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class Consumer {
    private Consumer() {}

    public static Customer binding() {
        var tags = new ArrayList<>(List.of("priority"));
        var customer = new Customer(
                new CustomerId("c-1"), "Ada", Optional.empty(), tags, Optional.empty(), Status.Active);
        tags.add("mutated");
        if (!customer.tags().equals(List.of("priority"))) throw new AssertionError("list must be immutable copy");
        return customer;
    }
}
