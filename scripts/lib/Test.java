package lib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test method of a script test: a no-argument instance method of a class named {@code *Test} under
 * {@code scripts/}. {@link RunTests} creates a fresh instance for every test method, so a constructor is the
 * set-up; a test class that implements {@link AutoCloseable} is closed after each test.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Test {}
