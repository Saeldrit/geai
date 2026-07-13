package io.sample.app;

public class GreetController {

    private final GreetService service = new GreetService();

    public String handle(String name) {
        if (name == null || name.isBlank()) {
            return "Hello, stranger";
        }
        return service.greet(name);
    }
}
