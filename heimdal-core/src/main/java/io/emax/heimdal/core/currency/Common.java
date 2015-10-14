package io.emax.heimdal.core.currency;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.emax.heimdal.api.currency.Monitor;
import io.emax.heimdal.api.currency.SigningType;
import io.emax.heimdal.api.currency.Wallet.Recipient;
import io.emax.heimdal.api.currency.Wallet.TransactionDetails;
import io.emax.heimdal.core.Application;
import io.emax.heimdal.core.cluster.ClusterInfo;
import io.emax.heimdal.core.cluster.Coordinator;
import io.emax.heimdal.core.cluster.CurrencyCommand;
import io.emax.heimdal.core.cluster.CurrencyCommandType;
import io.emax.heimdal.core.cluster.Server;
import rx.Subscription;

public class Common {
  static Logger logger = LoggerFactory.getLogger(Common.class);

  public static CurrencyParameters convertParams(String params) {
    try {
      JsonFactory jsonFact = new JsonFactory();
      JsonParser jsonParser = jsonFact.createParser(params);
      CurrencyParameters currencyParams =
          new ObjectMapper().readValue(jsonParser, CurrencyParameters.class);

      String userKey = currencyParams.getUserKey();
      currencyParams.setUserKey("");
      String sanitizedParams = stringifyObject(CurrencyParameters.class, currencyParams);
      currencyParams.setUserKey(userKey);

      logger.debug("[CurrencyParams] " + sanitizedParams);

      return currencyParams;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public static Object objectifyString(Class<?> objectType, String str) {
    try {
      JsonFactory jsonFact = new JsonFactory();
      JsonParser jsonParser = jsonFact.createParser(str);
      Object obj = new ObjectMapper().readValue(jsonParser, objectType);
      return obj;
    } catch (IOException e) {
      e.printStackTrace();
      return null;
    }
  }

  public static String stringifyObject(Class<?> objectType, Object obj) {
    try {
      JsonFactory jsonFact = new JsonFactory();
      ObjectMapper mapper = new ObjectMapper(jsonFact);
      ObjectWriter writer = mapper.writerFor(objectType);
      return writer.writeValueAsString(obj);
    } catch (IOException e) {
      e.printStackTrace();
      return "";
    }
  }

  public static CurrencyPackage lookupCurrency(CurrencyParameters params) {
    if (Application.getCurrencies().containsKey(params.getCurrencySymbol())) {
      return Application.getCurrencies().get(params.getCurrencySymbol());
    } else {
      return null;
    }
  }

  public static String listCurrencies() {
    List<String> currencies = new LinkedList<>();
    Application.getCurrencies().keySet().forEach((currency) -> {
      currencies.add(currency);
    });

    String currencyString = stringifyObject(LinkedList.class, currencies);

    logger.debug("[Response] " + currencyString);
    return currencyString;
  }

  public static String getNewAddress(String params) {
    CurrencyParameters currencyParams = convertParams(params);

    String response = "";
    CurrencyPackage currency = lookupCurrency(currencyParams);

    String userAccount = currency.getWallet().createAddress(currencyParams.getUserKey());
    LinkedList<String> accounts = new LinkedList<>();
    accounts.add(userAccount);
    String userMultiAccount =
        currency.getWallet().getMultiSigAddress(accounts, currencyParams.getUserKey());
    response = userMultiAccount;

    logger.debug("[Response] " + response);
    return response;
  }

  public static String listAllAddresses(String params) {
    CurrencyParameters currencyParams = convertParams(params);

    String response = "";
    CurrencyPackage currency = lookupCurrency(currencyParams);

    LinkedList<String> accounts = new LinkedList<>();
    currency.getWallet().getAddresses(currencyParams.getUserKey()).forEach(accounts::add);
    response = stringifyObject(LinkedList.class, accounts);

    logger.debug("[Response] " + response);
    return response;
  }

  public static String listTransactions(String params) {
    CurrencyParameters currencyParams = convertParams(params);

    String response = "";
    CurrencyPackage currency = lookupCurrency(currencyParams);

    LinkedList<TransactionDetails> txDetails = new LinkedList<>();
    currencyParams.getAccount().forEach(account -> {
      txDetails.addAll(Arrays.asList(currency.getWallet().getTransactions(account, 100, 0)));
    });

    response = stringifyObject(LinkedList.class, txDetails);

    logger.debug("[Response] " + response);
    return response;
  }

  public static String getBalance(String params) {
    CurrencyParameters currencyParams = convertParams(params);

    String response = "";
    CurrencyPackage currency = lookupCurrency(currencyParams);

    BigDecimal balance = new BigDecimal(0);
    if (currencyParams.getAccount() == null || currencyParams.getAccount().isEmpty()) {
      for (String account : currency.getWallet().getAddresses(currencyParams.getUserKey())) {
        balance = balance.add(new BigDecimal(currency.getWallet().getBalance(account)));
      }
    } else {
      for (String account : currencyParams.getAccount()) {
        balance = balance.add(new BigDecimal(currency.getWallet().getBalance(account)));
      }
    }

    response = balance.toPlainString();

    logger.debug("[Response] " + response);
    return response;
  }

  private static HashMap<String, Subscription> balanceSubscriptions = new HashMap<>();
  private static HashMap<String, Subscription> transactionSubscriptions = new HashMap<>();
  private static HashMap<String, Monitor> monitors = new HashMap<>();

  private static void cleanUpSubscriptions(String id) {
    if (balanceSubscriptions.containsKey(id)) {
      balanceSubscriptions.get(id).unsubscribe();
      balanceSubscriptions.remove(id);
    }

    if (transactionSubscriptions.containsKey(id)) {
      transactionSubscriptions.get(id).unsubscribe();
      transactionSubscriptions.remove(id);
    }

    if (monitors.containsKey(id)) {
      monitors.get(id).destroyMonitor();
      monitors.remove(id);
    }
  }

  public static String monitorBalance(String params, AtmosphereResponse responseSocket) {
    CurrencyParameters currencyParams = convertParams(params);

    String response = "";
    CurrencyPackage currency = lookupCurrency(currencyParams);

    Monitor monitor = currency.getMonitor().createNewMonitor();

    monitor.addAddresses(currencyParams.getAccount());

    CurrencyParameters returnParms = new CurrencyParameters();
    response = stringifyObject(CurrencyParameters.class, returnParms);

    // Web socket was passed to us
    if (responseSocket != null) {

      cleanUpSubscriptions(responseSocket.uuid());

      Subscription wsBalanceSubscription =
          monitor.getObservableBalances().subscribe((balanceMap) -> {
            balanceMap.forEach((address, balance) -> {
              try {
                CurrencyParameters responseParms = new CurrencyParameters();
                responseParms.setAccount(new LinkedList<String>());
                responseParms.getAccount().add(address);
                CurrencyParametersRecipient accountData = new CurrencyParametersRecipient();
                accountData.setAmount(balance);
                accountData.setRecipientAddress(address);
                responseParms.setReceivingAccount(Arrays.asList(accountData));
                responseSocket.write(stringifyObject(CurrencyParameters.class, responseParms));
              } catch (Exception e) {
                cleanUpSubscriptions(responseSocket.uuid());
                return;
              }
            });
          });
      balanceSubscriptions.put(responseSocket.uuid(), wsBalanceSubscription);

      Subscription wsTransactionSubscription =
          monitor.getObservableTransactions().subscribe((transactionSet) -> {
            transactionSet.forEach((transaction) -> {
              try {
                CurrencyParameters responseParms = new CurrencyParameters();
                responseParms.setAccount(new LinkedList<String>());
                responseParms.getAccount().addAll(Arrays.asList(transaction.getFromAddress()));
                LinkedList<CurrencyParametersRecipient> receivers = new LinkedList<>();
                Arrays.asList(transaction.getToAddress()).forEach(address -> {
                  CurrencyParametersRecipient sendData = new CurrencyParametersRecipient();
                  sendData.setAmount(transaction.getAmount().toPlainString());
                  sendData.setRecipientAddress(address);
                  receivers.add(sendData);
                });
                responseParms.setReceivingAccount(receivers);
                responseParms.setTransactionData(transaction.getTxHash());
                responseSocket.write(stringifyObject(CurrencyParameters.class, responseParms));
              } catch (Exception e) {
                cleanUpSubscriptions(responseSocket.uuid());
                return;
              }
            });
          });

      transactionSubscriptions.put(responseSocket.uuid(), wsTransactionSubscription);
      monitors.put(responseSocket.uuid(), monitor);
    } else if (currencyParams.getCallback() != null && !currencyParams.getCallback().isEmpty()) {
      // It's a REST callback
      cleanUpSubscriptions(currencyParams.getCallback());

      Subscription rsBalanceSubscription =
          monitor.getObservableBalances().subscribe((balanceMap) -> {
            balanceMap.forEach((address, balance) -> {
              try {
                CurrencyParameters responseParms = new CurrencyParameters();
                responseParms.setAccount(new LinkedList<String>());
                responseParms.getAccount().add(address);
                CurrencyParametersRecipient accountData = new CurrencyParametersRecipient();
                accountData.setAmount(balance);
                accountData.setRecipientAddress(address);
                responseParms.setReceivingAccount(Arrays.asList(accountData));

                HttpPost httpPost = new HttpPost(currencyParams.getCallback());
                httpPost.addHeader("content-type", "application/json");
                StringEntity entity;
                entity = new StringEntity(stringifyObject(CurrencyParameters.class, responseParms));
                httpPost.setEntity(entity);

                HttpClients.createDefault().execute(httpPost).close();
              } catch (Exception e) {
                cleanUpSubscriptions(currencyParams.getCallback());
                return;
              }
            });
          });
      balanceSubscriptions.put(currencyParams.getCallback(), rsBalanceSubscription);

      Subscription rsTransactionSubscription =
          monitor.getObservableTransactions().subscribe((transactionSet) -> {
            transactionSet.forEach((transaction) -> {
              try {
                CurrencyParameters responseParms = new CurrencyParameters();
                responseParms.setAccount(new LinkedList<String>());
                responseParms.getAccount().addAll(Arrays.asList(transaction.getFromAddress()));
                LinkedList<CurrencyParametersRecipient> receivers = new LinkedList<>();
                Arrays.asList(transaction.getToAddress()).forEach(address -> {
                  CurrencyParametersRecipient sendData = new CurrencyParametersRecipient();
                  sendData.setAmount(transaction.getAmount().toPlainString());
                  sendData.setRecipientAddress(address);
                  receivers.add(sendData);
                });
                responseParms.setReceivingAccount(receivers);
                responseParms.setTransactionData(transaction.getTxHash());

                HttpPost httpPost = new HttpPost(currencyParams.getCallback());
                httpPost.addHeader("content-type", "application/json");
                StringEntity entity;
                entity = new StringEntity(stringifyObject(CurrencyParameters.class, responseParms));
                httpPost.setEntity(entity);

                HttpClients.createDefault().execute(httpPost).close();
              } catch (Exception e) {
                cleanUpSubscriptions(currencyParams.getCallback());
                return;
              }
            });
          });
      transactionSubscriptions.put(currencyParams.getCallback(), rsTransactionSubscription);
      monitors.put(currencyParams.getCallback(), monitor);
    } else {
      // We have no way to respond to the caller other than with this response.
      monitor.destroyMonitor();
    }

    logger.debug("[Response] " + response);
    return response;
  }

  public static String prepareTransaction(String params) {
    CurrencyParameters currencyParams = convertParams(params);

    String response = "";
    CurrencyPackage currency = lookupCurrency(currencyParams);

    // Create the transaction
    List<String> addresses = new LinkedList<>();
    addresses.addAll(currencyParams.getAccount());
    LinkedList<Recipient> recipients = new LinkedList<>();
    currencyParams.getReceivingAccount().forEach(account -> {
      Recipient recipient = new Recipient();
      recipient.setAmount(new BigDecimal(account.getAmount()));
      recipient.setRecipientAddress(account.getRecipientAddress());
      recipients.add(recipient);
    });
    currencyParams
        .setTransactionData(currency.getWallet().createTransaction(addresses, recipients));

    // Authorize it with the user account
    String initalTx = currencyParams.getTransactionData();
    currencyParams.setTransactionData(currency.getWallet().signTransaction(initalTx,
        currencyParams.getAccount().get(0), currencyParams.getUserKey()));

    // If the userKey/address combo don't work then we stop here.
    if (currencyParams.getTransactionData().equalsIgnoreCase(initalTx)) {
      return initalTx;
    }

    // Send it if it's a sign-each and there's more than one signature
    // required (we're at 1/X)
    if (currency.getConfiguration().getMinSignatures() > 1
        && currency.getConfiguration().getSigningType().equals(SigningType.SENDEACH)) {
      submitTransaction(stringifyObject(CurrencyParameters.class, currencyParams));
    }

    response = currencyParams.getTransactionData();
    logger.debug("[Response] " + response);
    return response;
  }

  public static String approveTransaction(String params, boolean sendToRemotes) {
    CurrencyParameters currencyParams = convertParams(params);

    String response = "";
    CurrencyPackage currency = lookupCurrency(currencyParams);

    for (Server server : ClusterInfo.getInstance().getServers()) {
      if (server.isOriginator()) { // It's us, try to sign it locally.
        currencyParams.setTransactionData(currency.getWallet().signTransaction(
            currencyParams.getTransactionData(), currencyParams.getAccount().get(0)));
      } else if (sendToRemotes) {
        CurrencyCommand command = new CurrencyCommand();
        command.setCurrencyParams(currencyParams);
        command.setCommandType(CurrencyCommandType.SIGN);
        command = CurrencyCommand.parseCommandString(Coordinator.BroadcastCommand(command, server));

        if (command != null) {
          String originalTx = currencyParams.getTransactionData();
          currencyParams.setTransactionData(command.getCurrencyParams().getTransactionData());

          // If it's send-each and the remote actually signed it, send it.
          if (!originalTx.equalsIgnoreCase(currencyParams.getTransactionData())
              && currency.getConfiguration().getSigningType().equals(SigningType.SENDEACH)) {
            submitTransaction(stringifyObject(CurrencyParameters.class, currencyParams));
          }
        }
      }
    }
    response = currencyParams.getTransactionData();

    logger.debug("[Response] " + response);
    return response;
  }

  public static String submitTransaction(String params) {
    CurrencyParameters currencyParams = convertParams(params);

    String response = "";
    CurrencyPackage currency = lookupCurrency(currencyParams);
    response = currency.getWallet().sendTransaction(currencyParams.getTransactionData());

    logger.debug("[Response] " + response);
    return response;
  }

}
