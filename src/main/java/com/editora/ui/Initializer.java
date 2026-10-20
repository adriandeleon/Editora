package com.editora.ui;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method that finishes building an object its constructor leaves half-made, such as
 * {@link MainController#init}, which {@code WindowManager} calls right after FXMLLoader constructs the controller.
 * NullAway treats any annotation named {@code Initializer} this way: a field this method (or a private method it
 * calls) always assigns counts as non-null, as if the constructor had set it. That is only true when every caller
 * runs the method before anything else touches the object, so use it for that lifecycle and nothing else
 * (docs/conventions.md, "Null annotations").
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Initializer {}
