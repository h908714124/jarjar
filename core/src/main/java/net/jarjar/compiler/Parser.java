package net.jarjar.compiler;

import com.squareup.javapoet.TypeSpec;

class Parser {

  private final Context context;

  private Parser(Context context) {
    this.context = context;
  }

  static Parser create(Context context) {
    return new Parser(context);
  }

  TypeSpec define() {
    TypeSpec.Builder spec = TypeSpec.classBuilder(context.generatedClass);
    return spec.build();
  }
}
