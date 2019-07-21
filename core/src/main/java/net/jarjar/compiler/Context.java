package net.jarjar.compiler;

import com.squareup.javapoet.ClassName;

import javax.lang.model.element.TypeElement;

final class Context {

  // the annotated class
  final TypeElement sourceType;

  // the *_Parser class that will be generated
  final ClassName generatedClass;

  private Context(TypeElement sourceType, ClassName generatedClass) {
    this.sourceType = sourceType;
    this.generatedClass = generatedClass;
  }

  static Context create(TypeElement sourceType, ClassName generatedClass) {
    return new Context(sourceType, generatedClass);
  }
}
