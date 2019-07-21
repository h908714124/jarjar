package net.jarjar.compiler;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import net.jarjar.JsonType;
import net.jarjar.JsonValue;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toSet;
import static javax.lang.model.element.Modifier.ABSTRACT;
import static javax.lang.model.util.ElementFilter.methodsIn;

public final class Processor extends AbstractProcessor {

  private final boolean debug;

  public Processor() {
    this(false);
  }

  // visible for testing
  Processor(boolean debug) {
    this.debug = debug;
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return Stream.of(JsonType.class, JsonValue.class)
        .map(Class::getCanonicalName)
        .collect(toSet());
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
    try {
      TypeTool.init(processingEnv.getTypeUtils(), processingEnv.getElementUtils());
      processInternal(annotations, env);
    } finally {
      TypeTool.unset();
    }
    return false;
  }

  private void processInternal(Set<? extends TypeElement> annotations, RoundEnvironment env) {
    Set<String> annotationsToProcess = annotations.stream().map(TypeElement::getQualifiedName).map(Name::toString).collect(toSet());
    try {
      validateAnnotatedMethods(env, annotationsToProcess);
    } catch (ValidationException e) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage(), e.about);
      return;
    }
    if (!annotationsToProcess.contains(JsonType.class.getCanonicalName())) {
      return;
    }
    processAnnotatedTypes(getAnnotatedTypes(env));
  }

  private void processAnnotatedTypes(Set<TypeElement> annotatedClasses) {
    for (TypeElement sourceType : annotatedClasses) {
      ClassName generatedClass = generatedClass(ClassName.get(sourceType));
      try {
        validateType(sourceType);

        Context context = Context.create(
            sourceType,
            generatedClass);
        TypeSpec typeSpec = Parser.create(context).define();
        write(sourceType, generatedClass, typeSpec);
      } catch (ValidationException e) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, e.getMessage(), e.about);
      } catch (AssertionError error) {
        handleUnknownError(sourceType, error);
      }
    }
  }

  private Set<TypeElement> getAnnotatedTypes(RoundEnvironment env) {
    Set<? extends Element> annotated = env.getElementsAnnotatedWith(JsonType.class);
    return ElementFilter.typesIn(annotated);
  }

  private void write(
      TypeElement sourceType,
      ClassName generatedType,
      TypeSpec definedType) {
    JavaFile.Builder builder = JavaFile.builder(generatedType.packageName(), definedType);
    JavaFile javaFile = builder
        .skipJavaLangImports(true)
        .build();
    try {
      JavaFileObject sourceFile = processingEnv.getFiler()
          .createSourceFile(generatedType.toString(),
              javaFile.typeSpec.originatingElements.toArray(new Element[0]));
      try (Writer writer = sourceFile.openWriter()) {
        String sourceCode = javaFile.toString();
        writer.write(sourceCode);
        if (debug) {
          System.err.println("##############");
          System.err.println("# Debug info #");
          System.err.println("##############");
          System.err.println(sourceCode);
        }
      } catch (IOException e) {
        handleUnknownError(sourceType, e);
      }
    } catch (IOException e) {
      handleUnknownError(sourceType, e);
    }
  }

  private void validateType(TypeElement sourceType) {
    if (sourceType.getKind() == ElementKind.INTERFACE) {
      throw ValidationException.create(sourceType,
          "Use an abstract class, not an interface.");
    }
    if (!TypeTool.get().isSameType(sourceType.getSuperclass(), Object.class)) {
      throw ValidationException.create(sourceType,
          String.format("The class may not extend %s.", sourceType.getSuperclass()));
    }
    if (!sourceType.getModifiers().contains(ABSTRACT)) {
      throw ValidationException.create(sourceType,
          "Use an abstract class.");
    }
    if (sourceType.getModifiers().contains(Modifier.PRIVATE)) {
      throw ValidationException.create(sourceType,
          "The class cannot not be private.");
    }
    if (sourceType.getNestingKind().isNested() &&
        !sourceType.getModifiers().contains(Modifier.STATIC)) {
      throw ValidationException.create(sourceType,
          "The nested class must be static.");
    }
    if (!sourceType.getInterfaces().isEmpty()) {
      throw ValidationException.create(sourceType,
          "The class cannot implement anything.");
    }
    if (!sourceType.getTypeParameters().isEmpty()) {
      throw ValidationException.create(sourceType,
          "The class cannot have type parameters.");
    }
    if (!Util.hasDefaultConstructor(sourceType)) {
      throw ValidationException.create(sourceType,
          "The class must have a default constructor.");
    }
  }

  private void validateAnnotatedMethods(
      RoundEnvironment env, Set<String> annotationsToProcess) {
    List<ExecutableElement> methods = getAnnotatedMethods(env, annotationsToProcess);
    for (ExecutableElement method : methods) {
      Element enclosingElement = method.getEnclosingElement();
      if (enclosingElement.getAnnotation(JsonType.class) == null) {
        throw ValidationException.create(enclosingElement,
            "The class must have the " +
                JsonType.class.getSimpleName() + " annotation.");
      }
      if (!enclosingElement.getModifiers().contains(ABSTRACT)) {
        throw ValidationException.create(enclosingElement,
            "The class must be abstract");
      }
      if (!method.getModifiers().contains(ABSTRACT)) {
        throw ValidationException.create(method,
            "The method must be abstract.");
      }
      if (!method.getParameters().isEmpty()) {
        throw ValidationException.create(method,
            "The method may not have parameters.");
      }
      if (!method.getTypeParameters().isEmpty()) {
        throw ValidationException.create(method,
            "The method may not have type parameters.");
      }
      if (!method.getThrownTypes().isEmpty()) {
        throw ValidationException.create(method,
            "The method may not declare any exceptions.");
      }
    }
  }

  private List<ExecutableElement> getAnnotatedMethods(
      RoundEnvironment env, Set<String> annotationsToProcess) {
    Set<? extends Element> parameters =
        annotationsToProcess.contains(JsonValue.class.getCanonicalName()) ?
            env.getElementsAnnotatedWith(JsonValue.class) :
            emptySet();
    List<ExecutableElement> methods = new ArrayList<>(parameters.size());
    methods.addAll(methodsIn(parameters));
    return methods;
  }

  private static ClassName generatedClass(ClassName type) {
    String name = String.join("_", type.simpleNames()) + "_Parser";
    return type.topLevelClassName().peerClass(name);
  }

  private void handleUnknownError(
      TypeElement sourceType,
      Throwable e) {
    String message = String.format("Unexpected error while processing %s: %s", sourceType, e.getMessage());
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, sourceType);
  }
}
