# Comparison with google-java-format and Prettier

All three outputs below were produced from the same input, at 100 columns and a 2-space indent. The tools were
google-java-format 1.36.1 and prettier 3.9.6 with prettier-plugin-java 2.10.3, which are the versions
[OpenTripPlanner](https://github.com/opentripplanner/OpenTripPlanner) uses. google-java-format has a fixed 2-space
indent with a 4-space continuation, so its wrapped lines are indented more.

## The input

```java
  String describe(Order order, Customer customer, Clock clock) {
    if (order.isPaid() && customer.hasVerifiedEmail() && !customer.isBlocked() && order.total() > customer.creditLimit()) {
      return "needs review";
    }
    String message = "Order " + order.id() + " for customer " + customer.name() + " was placed at " + clock.now() + " and is pending";
    int count = 0; // number of retries
    for (Item item : order.items()) {
      count += item.quantity();
    }
    return switch (order.status()) {
      case NEW, PENDING_PAYMENT, PENDING_SHIPMENT, PENDING_CONFIRMATION_FROM_CUSTOMER -> "Order is still waiting for something to happen";
      case SHIPPED -> "shipped";
      default -> message;
    };
  }

  List<String> names(List<Customer> customers) {
    return customers.stream().filter(c -> c.isActive() && c.hasVerifiedEmail()).map(c -> c.firstName() + " " + c.lastName()).sorted().toList();
  }

  /**
   * Finds the thing.<p>Second paragraph that is quite long and goes well past the column limit so it has to be wrapped somewhere.
   * @param id the id
   * @return the thing
   */
  Thing find(String id) {
    return repository.findByIdentifierAndOwnerAndStatus(id, currentUser().identifier(), Status.ACTIVE).orElseThrow(() -> new NotFoundException(id));
  }
}
```

## This formatter

```java
  String describe(Order order, Customer customer, Clock clock) {
    if (
      order.isPaid()
      && customer.hasVerifiedEmail()
      && !customer.isBlocked()
      && order.total() > customer.creditLimit()
    ) {
      return "needs review";
    }

    String message =
      "Order "
      + order.id()
      + " for customer "
      + customer.name()
      + " was placed at "
      + clock.now()
      + " and is pending";
    // number of retries
    int count = 0;

    for (Item item : order.items()) {
      count += item.quantity();
    }

    return switch (order.status()) {
      case NEW, PENDING_PAYMENT, PENDING_SHIPMENT, PENDING_CONFIRMATION_FROM_CUSTOMER
        -> "Order is still waiting for something to happen";
      case SHIPPED -> "shipped";
      default -> message;
    };
  }

  List<String> names(List<Customer> customers) {
    return customers
      .stream()
      .filter(c -> c.isActive() && c.hasVerifiedEmail())
      .map(c -> c.firstName() + " " + c.lastName())
      .sorted()
      .toList();
  }

  /**
   * Finds the thing.
   * <p>
   * Second paragraph that is quite long and goes well past the column limit so it has to be wrapped
   * somewhere.
   *
   * @param id the id
   * @return the thing
   */
  Thing find(String id) {
    return repository
      .findByIdentifierAndOwnerAndStatus(id, currentUser().identifier(), Status.ACTIVE)
      .orElseThrow(() -> new NotFoundException(id));
  }
}
```

## google-java-format

```java
  String describe(Order order, Customer customer, Clock clock) {
    if (order.isPaid()
        && customer.hasVerifiedEmail()
        && !customer.isBlocked()
        && order.total() > customer.creditLimit()) {
      return "needs review";
    }
    String message =
        "Order "
            + order.id()
            + " for customer "
            + customer.name()
            + " was placed at "
            + clock.now()
            + " and is pending";
    int count = 0; // number of retries
    for (Item item : order.items()) {
      count += item.quantity();
    }
    return switch (order.status()) {
      case NEW, PENDING_PAYMENT, PENDING_SHIPMENT, PENDING_CONFIRMATION_FROM_CUSTOMER ->
          "Order is still waiting for something to happen";
      case SHIPPED -> "shipped";
      default -> message;
    };
  }

  List<String> names(List<Customer> customers) {
    return customers.stream()
        .filter(c -> c.isActive() && c.hasVerifiedEmail())
        .map(c -> c.firstName() + " " + c.lastName())
        .sorted()
        .toList();
  }

  /**
   * Finds the thing.
   *
   * <p>Second paragraph that is quite long and goes well past the column limit so it has to be
   * wrapped somewhere.
   *
   * @param id the id
   * @return the thing
   */
  Thing find(String id) {
    return repository
        .findByIdentifierAndOwnerAndStatus(id, currentUser().identifier(), Status.ACTIVE)
        .orElseThrow(() -> new NotFoundException(id));
  }
}
```

## Prettier

```java

  String describe(Order order, Customer customer, Clock clock) {
    if (
      order.isPaid() &&
      customer.hasVerifiedEmail() &&
      !customer.isBlocked() &&
      order.total() > customer.creditLimit()
    ) {
      return "needs review";
    }
    String message =
      "Order " +
      order.id() +
      " for customer " +
      customer.name() +
      " was placed at " +
      clock.now() +
      " and is pending";
    int count = 0; // number of retries
    for (Item item : order.items()) {
      count += item.quantity();
    }
    return switch (order.status()) {
      case
        NEW,
        PENDING_PAYMENT,
        PENDING_SHIPMENT,
        PENDING_CONFIRMATION_FROM_CUSTOMER -> "Order is still waiting for something to happen";
      case SHIPPED -> "shipped";
      default -> message;
    };
  }

  List<String> names(List<Customer> customers) {
    return customers
      .stream()
      .filter(c -> c.isActive() && c.hasVerifiedEmail())
      .map(c -> c.firstName() + " " + c.lastName())
      .sorted()
      .toList();
  }

  /**
   * Finds the thing.<p>Second paragraph that is quite long and goes well past the column limit so it has to be wrapped somewhere.
   * @param id the id
   * @return the thing
   */
  Thing find(String id) {
    return repository
      .findByIdentifierAndOwnerAndStatus(id, currentUser().identifier(), Status.ACTIVE)
      .orElseThrow(() -> new NotFoundException(id));
  }
}
```

## What differs

| | This formatter | google-java-format | Prettier |
| --- | --- | --- | --- |
| Binary operators in a wrapped expression | First on the line | First on the line | Last on the line |
| Opening of a long `if (` | `(` ends the line, the condition is indented | Condition starts on the `if` line | `(` ends the line, the condition is indented |
| Long `case` labels | Kept as written; `->` on its own line | Kept; `->` ends the label line | One label per line |
| First call of a chain | Split from the receiver | Stays with the receiver | Split from the receiver |
| Trailing `// comment` | Moved above the statement | Stays | Stays |
| Blank lines around blocks | Always one | Not added | Not added |
| Javadoc | Reformatted; `<p>` on its own line | Reformatted; `<p>Text` | Not touched |
| Spacing inside a line (`a=1`) | Normalized, except `<` and `>` and a few other unclear places | Normalized | Normalized |
| Indent | Configurable | Fixed (2, continuation 4) | Configurable |
| Width | Configurable | Fixed at 100 | Configurable |

The last three rows decide whether it suits you. If you want a fixed style with nothing to configure,
google-java-format is the stronger choice. If you already use Prettier and like its shape, but you want the operators
first, blank lines around blocks and Javadoc handled, this formatter is a close fit. See
[Limitations](limitations.md) for what is still missing.
