package org.infinispan.client.hotrod.impl.transport.netty;

import java.io.File;
import java.net.SocketAddress;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.security.auth.Subject;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslClientFactory;
import javax.security.sasl.SaslException;

import org.infinispan.client.hotrod.configuration.AuthenticationConfiguration;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.SslConfiguration;
import org.infinispan.client.hotrod.impl.operations.OperationsFactory;
import org.infinispan.client.hotrod.logging.Log;
import org.infinispan.client.hotrod.logging.LogFactory;
import org.infinispan.commons.CacheConfigurationException;
import org.infinispan.commons.util.SaslUtils;
import org.infinispan.commons.util.SslContextFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;

class ChannelInitializer extends io.netty.channel.ChannelInitializer<Channel> {
   private static final Log log = LogFactory.getLog(ChannelInitializer.class);

   private final Bootstrap bootstrap;
   private final SocketAddress unresolvedAddress;
   private final OperationsFactory operationsFactory;
   private final Configuration configuration;
   private final ChannelFactory channelFactory;
   private ChannelPool channelPool;
   private volatile boolean isFirstPing = true;

   ChannelInitializer(Bootstrap bootstrap, SocketAddress unresolvedAddress, OperationsFactory operationsFactory, Configuration configuration, ChannelFactory channelFactory) {
      this.bootstrap = bootstrap;
      this.unresolvedAddress = unresolvedAddress;
      this.operationsFactory = operationsFactory;
      this.configuration = configuration;
      this.channelFactory = channelFactory;
   }

   CompletableFuture<Channel> createChannel() {
      ChannelFuture connect = bootstrap.clone().connect();
      ActivationFuture activationFuture = new ActivationFuture();
      connect.addListener(activationFuture);
      return activationFuture;
   }

   @Override
   protected void initChannel(Channel channel) throws Exception {
      if (log.isTraceEnabled()) {
         log.tracef("Created channel %s", channel);
      }
      if (configuration.security().ssl().enabled()) {
         initSsl(channel);
      }

      AuthenticationConfiguration authentication = configuration.security().authentication();
      if (authentication.enabled()) {
         initAuthentication(channel, authentication);
      }

      if (configuration.connectionPool().minEvictableIdleTime() > 0) {
         channel.pipeline().addLast("idle-state-handler",
               new IdleStateHandler(0, 0, configuration.connectionPool().minEvictableIdleTime(), TimeUnit.MILLISECONDS));
      }
      ChannelRecord channelRecord = new ChannelRecord(unresolvedAddress, channelPool);
      channel.attr(ChannelRecord.KEY).set(channelRecord);
      if (isFirstPing) {
         isFirstPing = false;
         channel.pipeline().addLast(InitialPingHandler.NAME, new InitialPingHandler(operationsFactory.newPingOperation(false)));
      } else {
         channel.pipeline().addLast(ActivationHandler.NAME, ActivationHandler.INSTANCE);
      }
      channel.pipeline().addLast(HeaderDecoder.NAME, new HeaderDecoder(operationsFactory.getCodec(), channelFactory, configuration, operationsFactory.getListenerNotifier()));
      if (configuration.connectionPool().minEvictableIdleTime() > 0) {
         // This handler needs to be the last so that HeaderDecoder has the chance to cancel the idle event
         channel.pipeline().addLast(IdleStateHandlerProvider.NAME,
               new IdleStateHandlerProvider(configuration.connectionPool().minIdle(), channelPool));
      }
   }

   private void initSsl(Channel channel) {
      SslConfiguration ssl = configuration.security().ssl();
      SslContext sslContext;
      if (ssl.sslContext() == null) {
         SslContextBuilder builder = SslContextBuilder.forClient();
         try {
            if (ssl.keyStoreFileName() != null) {
               builder.keyManager(new SslContextFactory()
                     .keyStoreFileName(ssl.keyStoreFileName())
                     .keyStoreType(ssl.keyStoreType())
                     .keyStorePassword(ssl.keyStorePassword())
                     .keyAlias(ssl.keyAlias())
                     .keyStoreCertificatePassword(ssl.keyStoreCertificatePassword())
                     .classLoader(configuration.classLoader())
                     .getKeyManagerFactory());
            }
            if (ssl.trustStoreFileName() != null) {
               if ("pem".equalsIgnoreCase(ssl.trustStoreType())) {
                  builder.trustManager(new File(ssl.trustStoreFileName()));
               } else {
                  builder.trustManager(new SslContextFactory()
                        .trustStoreFileName(ssl.trustStoreFileName())
                        .trustStoreType(ssl.trustStoreType())
                        .trustStorePassword(ssl.trustStorePassword())
                        .classLoader(configuration.classLoader())
                        .getTrustManagerFactory());
               }
            }
            if (ssl.trustStorePath() != null) {
               builder.trustManager(new File(ssl.trustStorePath()));
            }
            if (ssl.protocol() != null) {
               builder.protocols(ssl.protocol());
            }
            sslContext = builder.build();
         } catch (Exception e) {
            throw new CacheConfigurationException(e);
         }
      } else {
         sslContext = new JdkSslContext(ssl.sslContext(), true, ClientAuth.NONE);
      }
      SslHandler sslHandler = sslContext.newHandler(channel.alloc(), ssl.sniHostName(), -1);
      if (ssl.sniHostName() != null) {
         SSLParameters sslParameters = sslHandler.engine().getSSLParameters();
         sslParameters.setServerNames(Collections.singletonList(new SNIHostName(ssl.sniHostName())));
         sslHandler.engine().setSSLParameters(sslParameters);
      }
      channel.pipeline().addFirst(sslHandler,
            SslHandshakeExceptionHandler.INSTANCE);
   }

   private void initAuthentication(Channel channel, AuthenticationConfiguration authentication) throws PrivilegedActionException, SaslException {
      SaslClient saslClient;
      SaslClientFactory scf = getSaslClientFactory(authentication);
      SslHandler sslHandler = channel.pipeline().get(SslHandler.class);
      Principal principal = sslHandler != null ? sslHandler.engine().getSession().getLocalPrincipal() : null;
      String authorizationId = principal != null ? principal.getName() : null;
      if (authentication.clientSubject() != null) {
         // We must use Subject.doAs() instead of Security.doAs()
         saslClient = Subject.doAs(authentication.clientSubject(), (PrivilegedExceptionAction<SaslClient>) () ->
               scf.createSaslClient(new String[]{authentication.saslMechanism()}, authorizationId, "hotrod",
                     authentication.serverName(), authentication.saslProperties(), authentication.callbackHandler())
         );
      } else {
         saslClient = scf.createSaslClient(new String[]{authentication.saslMechanism()}, authorizationId, "hotrod",
               authentication.serverName(), authentication.saslProperties(), authentication.callbackHandler());
      }

      channel.pipeline().addLast(AuthHandler.NAME, new AuthHandler(authentication, saslClient, operationsFactory));
   }

   private SaslClientFactory getSaslClientFactory(AuthenticationConfiguration configuration) {
      if (log.isTraceEnabled()) {
         log.tracef("Attempting to load SaslClientFactory implementation with mech=%s, props=%s",
               configuration.saslMechanism(), configuration.saslProperties());
      }
      Collection<SaslClientFactory> clientFactories = SaslUtils.getSaslClientFactories(this.getClass().getClassLoader(), true);
      for (SaslClientFactory saslFactory : clientFactories) {
         try {
            String[] saslFactoryMechs = saslFactory.getMechanismNames(configuration.saslProperties());
            for (String supportedMech : saslFactoryMechs) {
               if (supportedMech.equals(configuration.saslMechanism())) {
                  if (log.isTraceEnabled()) {
                     log.tracef("Loaded SaslClientFactory: %s", saslFactory.getClass().getName());
                  }
                  return saslFactory;
               }

            }
         } catch (Throwable t) {
            // Catch any errors that can happen when calling to a Sasl mech
            log.tracef("Error while trying to obtain mechanism names supported by SaslClientFactory: %s", saslFactory.getClass().getName());
         }
      }
      throw new IllegalStateException("SaslClientFactory implementation now found");
   }

   void setChannelPool(ChannelPool channelPool) {
      this.channelPool = channelPool;
   }

   private static class ActivationFuture extends CompletableFuture<Channel> implements ChannelFutureListener, BiConsumer<Channel, Throwable> {
      @Override
      public void operationComplete(ChannelFuture future) throws Exception {
         if (future.isSuccess()) {
            Channel channel = future.channel();
            ChannelRecord.of(channel).whenComplete(this);
         } else {
            completeExceptionally(future.cause());
         }
      }

      @Override
      public void accept(Channel channel, Throwable throwable) {
         if (throwable != null) {
            completeExceptionally(throwable);
         } else {
            complete(channel);
         }
      }
   }
}
