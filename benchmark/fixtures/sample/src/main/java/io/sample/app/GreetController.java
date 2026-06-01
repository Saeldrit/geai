package io.sample.app;

/** Minimal entry point that exposes the greeting use case. */
public class GreetController {

    private final GreetService service = new GreetService();

    public String handle(String name) {
        if (name == null || name.isBlank()) {
            return "Hello, stranger";
        }
        return service.greet(name);
    }
}
