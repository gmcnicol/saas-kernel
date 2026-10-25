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
        var capability = new io.github.gmcnicol.kernel.application.TypedCapability<>(
                example.bindings.customer.crm.ManageCustomer.TYPE,
                new example.bindings.customer.crm.ManageCustomer(Optional.of(2)));
        var model = new io.github.gmcnicol.kernel.application.TypedAuthorisationModel<>(
                Customer.TYPE, Set.of(Customer.NAME), Set.of(), Set.of(capability.type()));
        if (!example.bindings.GeneratedSemanticBindings.INSTANCE.capabilities().contains(capability.type())
                || !model.requiredCapabilities().contains(capability.type())) {
            throw new AssertionError("capability must be generated, assembled and explicitly required");
        }
        io.github.gmcnicol.kernel.application.ReadPath<Customer, String> namePath = Customer.READ_NAME;
        if (!Customer.TYPE.readPaths().contains(namePath)) throw new AssertionError("generated read path missing");
        io.github.gmcnicol.kernel.application.ReadPath<example.bindings.customer.crm.CustomerRead, String> nested =
                example.bindings.customer.crm.CustomerRead.READ_DETAILS_LABEL;
        if (!example.bindings.customer.crm.CustomerRead.TYPE.readPaths().contains(nested)
                || !example.bindings.GeneratedSemanticBindings.INSTANCE.subjects().contains(RatioId.TYPE)) {
            throw new AssertionError("nested leaves and standalone scope Subjects must be inventoried");
        }
        // Existing generated inventories and Application constructors remain source compatible.
        var bindings = example.bindings.GeneratedSemanticBindings.INSTANCE;
        new io.github.gmcnicol.kernel.semanticpack.SemanticBindings(
                bindings.projections(), bindings.facts(), bindings.candidates(), bindings.events(), bindings.actions());
        new io.github.gmcnicol.kernel.semanticpack.SemanticBindings(bindings.projections(), bindings.facts());
        new io.github.gmcnicol.kernel.semanticpack.SemanticBindings(bindings.projections(), bindings.facts(), bindings.candidates(), bindings.events(), bindings.actions(), bindings.capabilities());
        new io.github.gmcnicol.kernel.application.TypedAuthorisationModel<>(Customer.TYPE, Set.of(Customer.NAME));
        new io.github.gmcnicol.kernel.application.TypedAuthorisationModel<>(Customer.TYPE, Set.of(Customer.NAME), Set.of());
        return customer;
    }

    public static void readConsumer(io.github.gmcnicol.kernel.application.AuthorisedReads reads,
            io.github.gmcnicol.kernel.application.ReadRequest<example.bindings.customer.crm.CustomerRead> request,
            io.github.gmcnicol.kernel.application.ReadSource<example.bindings.customer.crm.CustomerRead> source) {
        java.util.Optional<io.github.gmcnicol.kernel.application.AuthorisedRecord<example.bindings.customer.crm.CustomerRead>> one = reads.one(request, source);
        java.util.List<io.github.gmcnicol.kernel.application.AuthorisedRecord<example.bindings.customer.crm.CustomerRead>> many = reads.many(request, source);
        one.ifPresent(view -> {
            io.github.gmcnicol.kernel.application.ReadValue<String> label = view.get(example.bindings.customer.crm.CustomerRead.READ_DETAILS_LABEL);
        });
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
