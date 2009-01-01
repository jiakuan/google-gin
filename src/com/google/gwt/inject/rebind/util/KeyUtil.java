/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.gwt.inject.rebind.util;

import com.google.gwt.core.ext.typeinfo.JAbstractMethod;
import com.google.gwt.core.ext.typeinfo.JArrayType;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JField;
import com.google.gwt.core.ext.typeinfo.JMethod;
import com.google.gwt.core.ext.typeinfo.JParameter;
import com.google.gwt.core.ext.typeinfo.JParameterizedType;
import com.google.gwt.core.ext.typeinfo.JPrimitiveType;
import com.google.gwt.core.ext.typeinfo.JType;
import com.google.gwt.core.ext.typeinfo.JWildcardType;
import com.google.inject.BindingAnnotation;
import com.google.inject.Key;
import com.google.inject.ProvisionException;
import com.google.inject.Singleton;
import com.google.inject.util.Types;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Util object that offers {@link Key} retrieval and manipulation methods.
 */
@Singleton
public class KeyUtil {

  public Key<?> getKey(JMethod method) {
    if (isMemberInject(method)) {
      return getKey(method.getParameters()[0]);
    }

    return getKey(method.getReturnType(), getAnnotations(JAbstractMethod.class, method));
  }

  public Key<?> getKey(JParameter param) {
    return getKey(param.getType(), getAnnotations(JParameter.class, param));
  }

  public Key<?> getKey(JField field) {
    return getKey(field.getType(), getAnnotations(JField.class, field));
  }

  public boolean isMemberInject(JMethod method) {
    return method.getReturnType() == JPrimitiveType.VOID;
  }

  public Class<?> getRawType(Key<?> key) {
    Type type = key.getTypeLiteral().getType();
    if (type instanceof Class) {
      return (Class) type;
    } else if (type instanceof ParameterizedType) {
      return (Class) ((ParameterizedType) type).getRawType();
    }

    throw new ProvisionException("Can't get raw type for " + key);
  }

  /**
   * Gets the Guice binding key for a given GWT type with optional annotations.
   *
   * @param gwtType GWT type to convert in to a key
   * @param annotations Optional array of {@code Annotation}s. If this contains
   *     one and only one {@link BindingAnnotation}, it will be included in the
   *     key. If it includes more than one, an exception will be thrown.
   * @return Guice Key instance for this type/annotations
   * @throws com.google.inject.ProvisionException in case of any failure
   */
  public Key<?> getKey(JType gwtType, Annotation[] annotations)
      throws ProvisionException {
    try {
      Type type = gwtTypeToJavaType(gwtType);
      Annotation bindingAnnotation = getBindingAnnotation(annotations);

      if (bindingAnnotation == null) {
        return Key.get(type);
      } else {
        return Key.get(type, bindingAnnotation);
      }
    } catch (ClassNotFoundException e) {
      throw new ProvisionException("Error creating key for " + gwtType, e);
    } catch (NoSuchFieldException e) {
      throw new ProvisionException("Error creating key for " + gwtType, e);
    } catch (IllegalAccessException e) {
      throw new ProvisionException("Error creating key for " + gwtType, e);
    }
  }

  private Annotation getBindingAnnotation(Annotation[] annotations) {
    if (annotations == null || annotations.length == 0) {
      return null;
    }

    Annotation bindingAnnotation = null;
    for (Annotation annotation : annotations) {
      if (annotation.annotationType().getAnnotation(BindingAnnotation.class) != null) {
        if (bindingAnnotation != null) {
          throw new ProvisionException(">1 binding annotation found: "
              + annotation + ", " + bindingAnnotation);
        }

        bindingAnnotation = annotation;

        // Keep going so we can find any rogue additional binding annotations
      }
    }

    return bindingAnnotation;
  }

  private Type gwtTypeToJavaType(JType gwtType)
      throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
    JPrimitiveType primitiveType = gwtType.isPrimitive();
    if (primitiveType != null) {
      String boxClassName = primitiveType.getQualifiedBoxedSourceName();
      Class<?> boxClass = loadClass(boxClassName, ClassType.REGULAR);
      return (Class) boxClass.getField("TYPE").get(null);
    }

    JArrayType arrayType = gwtType.isArray();
    if (gwtType.isArray() != null) {
      Type componentType = gwtTypeToJavaType(arrayType.getComponentType());
      return Types.arrayOf(componentType);
    }

    JWildcardType wildcardType = gwtType.isWildcard();
    if (wildcardType != null) {
      Type baseType = gwtTypeToJavaType(wildcardType.getBaseType());

      switch (wildcardType.getBoundType()) {
        case EXTENDS:
          return Types.subtypeOf(baseType);
        case SUPER:
          return Types.subtypeOf(baseType);
        case UNBOUND:

      }
    }

    JParameterizedType parameterizedType = gwtType.isParameterized();
    if (gwtType.isParameterized() != null) {
      JClassType[] typeArgs = parameterizedType.getTypeArgs();
      List<Type> javaTypeArgs = new ArrayList<Type>();

      for (JClassType typeArg : typeArgs) {
        JWildcardType wildcard = typeArg.isWildcard();

        // TODO(schmitt):  This might not work, but I have no better idea.
        if (wildcard == null || wildcard.getBoundType() != JWildcardType.BoundType.UNBOUND) {
          javaTypeArgs.add(gwtTypeToJavaType(typeArg));
        }
      }

      javaTypeArgs.toArray(new Type[javaTypeArgs.size()]);

      Type rawType = gwtTypeToJavaType(parameterizedType.getRawType());
      return
          Types.newParameterizedType(rawType, javaTypeArgs.toArray(new Type[javaTypeArgs.size()]));
    }

    JClassType jClassType = gwtType.isClassOrInterface();
    if (gwtType.isClassOrInterface() != null) {
      return loadClass(jClassType.getQualifiedSourceName(), ClassType.getType(jClassType));
    }

    throw new ProvisionException("Unknown GWT type: " + gwtType);
  }

  // Wrapper around Class.forName that passes initialize=false. This is critical
  // because GWT client code (whose class names we may be referencing here)
  // can not necessarily have its static initializers run at rebind time.
  private static Class<?> loadClass(String className, ClassType classType)
      throws ClassNotFoundException {
    String resultingClassName = classType.getBinaryClassName(className);
    return Class.forName(resultingClassName, false, Thread.currentThread().getContextClassLoader());
  }

  // Reflective hack until getAnnotations is exposed from GWT
  private static <T> Annotation[] getAnnotations(Class<T> clazz, T instance) {
    try {
      Method method = clazz.getDeclaredMethod("getAnnotations");
      method.setAccessible(true);
      return (Annotation[]) method.invoke(instance);
    } catch (NoSuchMethodException e) {
      throw new ProvisionException("Failed to get annotations from " + instance, e);
    } catch (IllegalAccessException e) {
      throw new ProvisionException("Failed to get annotations from " + instance, e);
    } catch (InvocationTargetException e) {
      throw new ProvisionException("Failed to get annotations from " + instance, e);
    }
  }
}