// "Create parameter 'flag'" "true"
class Foo {
    void test(Object obj) {
        switch (obj) {
            case String s && flag<caret> -> System.out.println(1);
            default -> System.out.println(2);
        }
    }
}