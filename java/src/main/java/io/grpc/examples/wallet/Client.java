/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.wallet;

import static io.grpc.Metadata.ASCII_STRING_MARSHALLER;
import static java.util.concurrent.TimeUnit.SECONDS;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ChannelCredentials;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.wallet.stats.PriceRequest;
import io.grpc.examples.wallet.stats.PriceResponse;
import io.grpc.examples.wallet.stats.StatsGrpc;
import io.grpc.xds.XdsChannelCredentials;
import io.opencensus.trace.Tracing;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A client for the gRPC Wallet example. */
public class Client {
  private static final Logger logger = Logger.getLogger(Client.class.getName());

  private enum CredentialsType {
    INSECURE,
    XDS
  }

  static final String ALICE_TOKEN = "2bd806c9";
  static final String BOB_TOKEN = "81b637d8";
  static final Metadata.Key<String> SESSION_ID_MD_KEY =
      Metadata.Key.of("session_id", ASCII_STRING_MARSHALLER);
  static final Metadata.Key<String> ROUTE_MD_KEY =
      Metadata.Key.of("route", ASCII_STRING_MARSHALLER);

  private String command;
  private String walletServer = "localhost:18881";
  private String statsServer = "localhost:18882";
  private String user = "Alice";
  private String gcpClientProject = "";
  private String route = "";
  private boolean watch;
  private boolean unaryWatch;
  private boolean affinity;
  private CredentialsType credentialsType = CredentialsType.INSECURE;

  public void run() throws InterruptedException, ExecutionException {
    logger.info("Will try to run " + command);

    if (!gcpClientProject.isEmpty()) {
      Observability.registerExporters(gcpClientProject);
    }

    String target;
    if ("price".equals(command)) {
      target = statsServer;
    } else {
      target = walletServer;
    }
    ChannelCredentials channelCredentials =
        credentialsType == CredentialsType.XDS
            ? XdsChannelCredentials.create(InsecureChannelCredentials.create())
            : InsecureChannelCredentials.create();

    ManagedChannel managedChannel = Grpc.newChannelBuilder(target, channelCredentials).build();
    Metadata headers = new Metadata();
    if ("Alice".equals(user)) {
      headers.put(WalletInterceptors.TOKEN_MD_KEY, ALICE_TOKEN);
      headers.put(WalletInterceptors.MEMBERSHIP_MD_KEY, "premium");
    } else {
      headers.put(WalletInterceptors.TOKEN_MD_KEY, BOB_TOKEN);
      headers.put(WalletInterceptors.MEMBERSHIP_MD_KEY, "normal");
    }
    if (affinity) {
      headers.put(SESSION_ID_MD_KEY, "1234");
    }
    if (!route.isEmpty()) {
      headers.put(ROUTE_MD_KEY, route);
    }
    Channel channel =
        ClientInterceptors.intercept(managedChannel, new HeaderClientInterceptor(headers));

    try {
      if ("price".equals(command)) {
        StatsGrpc.StatsBlockingStub blockingStub = StatsGrpc.newBlockingStub(channel);
        if (watch) {
          Iterator<PriceResponse> responses =
              blockingStub.watchPrice(PriceRequest.getDefaultInstance());
          while (responses.hasNext()) {
            printPriceResponse(responses.next());
          }
        } else {
          PriceResponse response = blockingStub.fetchPrice(PriceRequest.getDefaultInstance());
          printPriceResponse(response);
        }
      } else {
        WalletGrpc.WalletBlockingStub blockingStub = WalletGrpc.newBlockingStub(channel);
        BalanceRequest request =
            BalanceRequest.newBuilder().setIncludeBalancePerAddress(true).build();
        if (watch) {
          Iterator<BalanceResponse> responses = blockingStub.withWaitForReady().watchBalance(
              request);
          while (responses.hasNext()) {
            printBalanceResponse(responses.next());
          }
        } else if (unaryWatch) {
          while (true) {
            try {
              BalanceResponse response = blockingStub.fetchBalance(request);
              printBalanceResponse(response);
            } catch (StatusRuntimeException e) {
              // Log RPC errors, but keep attempting the unary watch.
              logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
            }
            Thread.sleep(1000);
          }
        } else {
            BalanceResponse response = blockingStub.fetchBalance(request);
            printBalanceResponse(response);
        }
      }
    } catch (StatusRuntimeException e) {
      logger.log(Level.WARNING, "RPC failed: {0}", e.getStatus());
      return;
    } finally {
      managedChannel.shutdownNow().awaitTermination(5, SECONDS);
      if (gcpClientProject != "") {
        // For demo purposes, shutdown the trace exporter to flush any pending traces.
        Tracing.getExportComponent().shutdown();
      }
    }
  }

  private void printPriceResponse(PriceResponse response) {
    System.out.println("price: " + response.getPrice());
  }

  private void printBalanceResponse(BalanceResponse response) {
    System.out.println("total balance: " + response.getBalance());
    for (BalancePerAddress address : response.getAddressesList()) {
      System.out.println(
          "- address: " + address.getAddress() + ", balance: " + address.getBalance());
    }
  }

  void parseArgs(String[] args) {
    boolean usage = false;
    for (String arg : args) {
      if (!arg.startsWith("--")) {
        if (command != null) {
          System.err.println(
              "Command already specified. Additional flags must be of the form --arg=value: "
                  + arg);
          usage = true;
          break;
        } else if ("balance".equals(arg) || "price".equals(arg)) {
          command = arg;
          continue;
        } else {
          System.err.println("Command must be either balance or price: " + arg);
          usage = true;
          break;
        }
      }
      String[] parts = arg.substring(2).split("=", 2);
      String key = parts[0];
      if ("help".equals(key)) {
        usage = true;
        break;
      }
      if (parts.length != 2) {
        System.err.println("All flags must be of the form --arg=value");
        usage = true;
        break;
      }
      String value = parts[1];
      if ("wallet_server".equals(key)) {
        walletServer = value;
      } else if ("stats_server".equals(key)) {
        statsServer = value;
      } else if ("user".equals(key)) {
        if ("Alice".equals(value) || "Bob".equals(value)) {
          user = value;
        } else {
          System.err.println("User must be either Alice or Bob: " + value);
          usage = true;
          break;
        }
      } else if ("gcp_client_project".equals(key)) {
        gcpClientProject = value;
      } else if ("watch".equals(key)) {
        watch = Boolean.parseBoolean(value);
      } else if ("unary_watch".equals(key)) {
        unaryWatch = Boolean.parseBoolean(value);
      } else if ("affinity".equals(key)) {
        affinity = Boolean.parseBoolean(value);
      } else if ("route".equals(key)) {
        route = value;
      } else if ("creds".equals(key)) {
        credentialsType = CredentialsType.valueOf(value.toUpperCase());
      } else {
        System.err.println("Unknown argument: " + key);
        usage = true;
        break;
      }
    }
    if (!usage && command == null) {
      System.err.println("Must specify either balance or price command");
      usage = true;
    }
    if (usage) {
      Client c = new Client();
      System.out.println(
          "Usage: [balance|price] [ARGS...]"
              + "\n"
              + "balance: create channel to wallet_server and get balance.\n"
              + "price: create channel to stats_server and get price.\n"
              + "\n  --wallet_server=HOST      Address of the wallet service. Default "
              + c.walletServer
              + "\n  --stats_server=HOST       Address of the stats service. Default "
              + c.statsServer
              + "\n  --user=Alice|Bob          The user to call the RPCs. Default "
              + c.user
              + "\n  --gcp_client_project=STR GCP project. If set, metrics and traces will be "
              + "sent to Stackdriver. Default \"" + c.gcpClientProject + "\""
              + "\n  --watch=true|false        Whether to call the streaming RPC. Default "
              + c.watch
              + "\n  --unary_watch=true|false  Watch for balance updates with unary RPC"
              + " in loop (only applies to balance "
              + "\n                            command). Requires watch=false."
              + " Default "
              + c.unaryWatch
              + "\n  --affinity=true|false     Send requests with session affinity. Default "
              + c.affinity
              + "\n  --creds=insecure|xds  . Type of credentials to use on the client. "
              + "Default "
              + c.credentialsType.toString().toLowerCase()
              + "\n  --route                   A string value to set for the 'route' header. "
              + "Optional");
      System.exit(1);
    }
  }

  public static void main(String[] args) throws Exception {
    Client client = new Client();
    client.parseArgs(args);
    client.run();
  }

  private static class HeaderClientInterceptor implements ClientInterceptor {
    Metadata headersToAdd;

    HeaderClientInterceptor(Metadata headersToAdd) {
      this.headersToAdd = headersToAdd;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
      return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
          headers.merge(headersToAdd);
          super.start(
              new SimpleForwardingClientCallListener<RespT>(responseListener) {
                @Override
                public void onHeaders(Metadata headers) {
                  System.out.println(
                      "server host: " + headers.get(WalletInterceptors.HOSTNAME_MD_KEY));
                  super.onHeaders(headers);
                }
              },
              headers);
        }
      };
    }
  }
}
