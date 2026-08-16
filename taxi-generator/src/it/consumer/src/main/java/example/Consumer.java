package example;

import example.bindings.customer.crm.Customer;
import example.bindings.customer.crm.CustomerActions;
import example.bindings.customer.crm.ChangeCustomer;
import example.bindings.customer.crm.CustomerChanged;
import example.bindings.customer.crm.CustomerEvent;
import example.bindings.customer.crm.CustomerId;
import example.bindings.customer.crm.EnabledId;
import example.bindings.customer.crm.RatioId;
import example.bindings.customer.crm.Status;
import example.bindings.GeneratedSemanticRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class Consumer {
    private Consumer() {}

    public static Customer binding() {
        var tags = new ArrayList<>(List.of("priority"));
        var customer = new Customer(
                new CustomerId("c-1"), "Ada", Optional.empty(), tags, Optional.empty(), Status.Active);
        tags.add("mutated");
        if (!customer.tags().equals(List.of("priority"))) throw new AssertionError("list must be immutable copy");
        CustomerEvent event = new CustomerChanged(customer.id());
        if (!(event instanceof CustomerChanged)) throw new AssertionError("closed Event union must be sealed and usable");
        if (!CustomerActions.CHANGE.qualifiedName().equals("customer.crm.CustomerActions.change")) {
            throw new AssertionError("generated Action identity must be service plus operation");
        }
        if (!CustomerId.TYPE.fromExternalId("c-2").equals(new CustomerId("c-2"))) {
            throw new AssertionError("generated Subject descriptor must restore typed identity");
        }
        rejects(() -> EnabledId.TYPE.fromExternalId("corrupt"));
        rejects(() -> RatioId.TYPE.fromExternalId("NaN"));
        rejects(() -> RatioId.TYPE.fromExternalId("Infinity"));
        var decoded = GeneratedSemanticRegistry.INSTANCE.decodeForm(
                CustomerActions.CHANGE.qualifiedName(), ChangeCustomer.TYPE.qualifiedName(),
                ChangeCustomer.TYPE.contractVersion(),
                Map.of("note", List.of("changed")), Set.of(), Optional.empty(), Optional.empty());
        if (decoded.actionType() != CustomerActions.CHANGE
                || !decoded.value().equals(new ChangeCustomer("changed"))) {
            throw new AssertionError("generated registry must decode only its generated Candidate Payload");
        }
        return customer;
    }

    private static void rejects(Runnable parser) {
        try {
            parser.run();
            throw new AssertionError("malformed Subject identity must fail closed");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
