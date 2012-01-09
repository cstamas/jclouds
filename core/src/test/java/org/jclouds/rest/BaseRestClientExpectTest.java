/**
 * Licensed to jclouds, Inc. (jclouds) under one or more
 * contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  jclouds licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jclouds.rest;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.jclouds.rest.RestContextFactory.contextSpec;
import static org.jclouds.rest.RestContextFactory.createContext;
import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Properties;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.jclouds.Constants;
import org.jclouds.concurrent.MoreExecutors;
import org.jclouds.concurrent.SingleThreaded;
import org.jclouds.concurrent.config.ConfiguresExecutorService;
import org.jclouds.http.HttpCommandExecutorService;
import org.jclouds.http.HttpRequest;
import org.jclouds.http.HttpResponse;
import org.jclouds.http.HttpUtils;
import org.jclouds.http.IOExceptionRetryHandler;
import org.jclouds.http.config.ConfiguresHttpCommandExecutorService;
import org.jclouds.http.handlers.DelegatingErrorHandler;
import org.jclouds.http.handlers.DelegatingRetryHandler;
import org.jclouds.http.internal.BaseHttpCommandExecutorService;
import org.jclouds.http.internal.HttpWire;
import org.jclouds.io.Payload;
import org.jclouds.io.Payloads;
import org.jclouds.logging.config.NullLoggingModule;
import org.jclouds.util.Strings2;
import org.testng.annotations.Test;

import com.google.common.annotations.Beta;
import com.google.common.base.Function;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.inject.AbstractModule;
import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;

/**
 * 
 * Allows us to test a client via its side effects.
 * 
 * @author Adrian Cole
 */
@Test(groups = "unit")
@Beta
public abstract class BaseRestClientExpectTest<S> {
   /**
    * only needed when the client is simple and not registered fn rest.properties
    */
   @Target(TYPE)
   @Retention(RUNTIME)
   public static @interface RegisterContext {
      Class<?> sync();

      Class<?> async();
   }

   protected String provider = "mock";

   protected Module createModule() {
      return new Module() {

         @Override
         public void configure(Binder binder) {

         }

      };
   }
   
   protected Payload payloadFromResource(String resource) {
      return Payloads.newInputStreamPayload(getClass().getResourceAsStream(resource));
   }

   @SingleThreaded
   @Singleton
   public static class ExpectHttpCommandExecutorService extends BaseHttpCommandExecutorService<HttpRequest> {

      private final Function<HttpRequest, HttpResponse> fn;

      @Inject
      public ExpectHttpCommandExecutorService(Function<HttpRequest, HttpResponse> fn, HttpUtils utils,
               @Named(Constants.PROPERTY_IO_WORKER_THREADS) ExecutorService ioExecutor,
               IOExceptionRetryHandler ioRetryHandler, DelegatingRetryHandler retryHandler,
               DelegatingErrorHandler errorHandler, HttpWire wire) {
         super(utils, ioExecutor, retryHandler, ioRetryHandler, errorHandler, wire);
         this.fn = checkNotNull(fn, "fn");
      }

      @Override
      protected void cleanup(HttpRequest nativeResponse) {
         if (nativeResponse.getPayload() != null)
            nativeResponse.getPayload().release();
      }

      @Override
      protected HttpRequest convert(HttpRequest request) throws IOException, InterruptedException {
         return request;
      }

      @Override
      protected HttpResponse invoke(HttpRequest nativeRequest) throws IOException, InterruptedException {
         return fn.apply(nativeRequest);
      }
   }

   @ConfiguresHttpCommandExecutorService
   @ConfiguresExecutorService
   public static class ExpectModule extends AbstractModule {
      private final Function<HttpRequest, HttpResponse> fn;

      public ExpectModule(Function<HttpRequest, HttpResponse> fn) {
         this.fn = checkNotNull(fn, "fn");
      }

      @Override
      protected void configure() {
         bind(ExecutorService.class).annotatedWith(Names.named(Constants.PROPERTY_USER_THREADS)).toInstance(
                  MoreExecutors.sameThreadExecutor());
         bind(ExecutorService.class).annotatedWith(Names.named(Constants.PROPERTY_IO_WORKER_THREADS)).toInstance(
                  MoreExecutors.sameThreadExecutor());
         bind(new TypeLiteral<Function<HttpRequest, HttpResponse>>() {
         }).toInstance(fn);
         bind(HttpCommandExecutorService.class).to(ExpectHttpCommandExecutorService.class);
      }
   }

   protected S requestSendsResponse(final HttpRequest fn, final HttpResponse out) {
      return createClient(new Function<HttpRequest, HttpResponse>() {

         @Override
         public HttpResponse apply(HttpRequest command) {
            assertEquals(renderRequest(command), renderRequest(fn));
            return out;
         }
      });
   }

   private String renderRequest(HttpRequest request) {
      StringBuilder builder = new StringBuilder().append(request.getRequestLine()).append('\n');
      for (Entry<String, String> header : request.getHeaders().entries()) {
         builder.append(header.getKey()).append(": ").append(header.getValue()).append('\n');
      }
      if (request.getPayload() != null) {
         for (Entry<String, String> header : HttpUtils.getContentHeadersFromMetadata(
                  request.getPayload().getContentMetadata()).entries()) {
            builder.append(header.getKey()).append(": ").append(header.getValue()).append('\n');
         }
         try {
            builder.append('\n').append(Strings2.toStringAndClose(request.getPayload().getInput()));
         } catch (IOException e) {
            throw Throwables.propagate(e);
         }

      } else {
         builder.append('\n');
      }
      return builder.toString();
   }

   protected S createClient(Function<HttpRequest, HttpResponse> fn) {
      return createClient(fn, createModule(), setupProperties());
   }

   protected S createClient(Function<HttpRequest, HttpResponse> fn, Module module) {
      return createClient(fn, module, setupProperties());

   }

   protected S createClient(Function<HttpRequest, HttpResponse> fn, Properties props) {
      return createClient(fn, createModule(), props);

   }

   protected S createClient(Function<HttpRequest, HttpResponse> fn, Module module, Properties props) {
      RestContextSpec<S, ?> contextSpec = makeContextSpec();

      return createContext(contextSpec,
               ImmutableSet.<Module> of(new ExpectModule(fn), new NullLoggingModule(), module), props).getApi();
   }

   @SuppressWarnings("unchecked")
   private RestContextSpec<S, ?> makeContextSpec() {
      if (getClass().isAnnotationPresent(RegisterContext.class))
         return (RestContextSpec<S, ?>) contextSpec(provider, "http://mock", "1", "", "", "userfoo", null, getClass()
                  .getAnnotation(RegisterContext.class).sync(),
                  getClass().getAnnotation(RegisterContext.class).async(), ImmutableSet.<Module> of());
      else
         return new RestContextFactory(setupRestProperties()).createContextSpec(provider, "identity", "credential",
                  new Properties());
   }


   protected Properties setupRestProperties() {
      return RestContextFactory.getPropertiesFromResource("/rest.properties");
   }

   protected Properties setupProperties() {
      return new Properties();
   }
}