package io.emax.cosigner.bitcoin;

import io.emax.cosigner.bitcoin.bitcoindrpc.BitcoindRpc;
import io.emax.cosigner.bitcoin.bitcoindrpc.MultiSig;
import io.emax.cosigner.bitcoin.bitcoindrpc.Outpoint;
import io.emax.cosigner.bitcoin.bitcoindrpc.OutpointDetails;
import io.emax.cosigner.bitcoin.bitcoindrpc.Output;
import io.emax.cosigner.bitcoin.bitcoindrpc.Payment;
import io.emax.cosigner.bitcoin.bitcoindrpc.Payment.PaymentCategory;
import io.emax.cosigner.bitcoin.bitcoindrpc.RawInput;
import io.emax.cosigner.bitcoin.bitcoindrpc.RawOutput;
import io.emax.cosigner.bitcoin.bitcoindrpc.RawTransaction;
import io.emax.cosigner.bitcoin.bitcoindrpc.SigHash;
import io.emax.cosigner.bitcoin.bitcoindrpc.SignedTransaction;
import io.emax.cosigner.bitcoin.common.BitcoinTools;
import io.emax.cosigner.common.ByteUtilities;
import io.emax.cosigner.common.crypto.Secp256k1;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Subscription;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BitcoinWallet implements io.emax.cosigner.api.currency.Wallet {
  private static final Logger logger = LoggerFactory.getLogger(BitcoinWallet.class);
  private static BitcoinConfiguration config = new BitcoinConfiguration();
  private static BitcoindRpc bitcoindRpc = BitcoinResource.getResource().getBitcoindRpc();
  private static final String PUBKEY_PREFIX = "PK-";
  @SuppressWarnings("unused")
  private static Subscription multiSigSubscription = Observable.interval(1, TimeUnit.MINUTES)
      .onErrorReturn(null).subscribe(tick -> scanForAddresses());

  private static HashMap<String, String> multiSigRedeemScripts = new HashMap<>();

  public BitcoinWallet() {

  }

  @Override
  public String createAddress(String name) {
    return createAddress(name, 0);
  }

  @Override
  public String createAddress(String name, int skipNumber) {
    int rounds = 1 + skipNumber;
    String privateKey =
        BitcoinTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
    String newAddress = BitcoinTools.getPublicAddress(privateKey);
    String pubKey = BitcoinTools.getPublicKey(privateKey);
    // Hash the user's key so it's not stored in the wallet
    String internalName = PUBKEY_PREFIX + pubKey;

    String[] existingAddresses = bitcoindRpc.getaddressesbyaccount(internalName);
    boolean oldAddress = true;

    while (oldAddress && rounds <= config.getMaxDeterministicAddresses()) {
      oldAddress = false;
      for (int i = 0; i < existingAddresses.length; i++) {
        if (existingAddresses[i].equalsIgnoreCase(newAddress)) {
          oldAddress = true;
          rounds++;
          privateKey =
              BitcoinTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
          newAddress = BitcoinTools.getPublicAddress(privateKey);
          break;
        }
      }
    }
    bitcoindRpc.importaddress(newAddress, internalName, true);

    return newAddress;
  }

  @Override
  public boolean registerAddress(String address) {
    bitcoindRpc.importaddress(address, "", true);
    return true;
  }

  @Override
  public Iterable<String> getAddresses(String name) {
    // Hash the user's key so it's not stored in the wallet
    String internalName = BitcoinTools.encodeUserKey(name);

    String[] addresses = bitcoindRpc.getaddressesbyaccount(internalName);
    return Arrays.asList(addresses);
  }

  @Override
  public String getMultiSigAddress(Iterable<String> addresses, String name) {
    // Hash the user's key so it's not stored in the wallet
    String internalName = BitcoinTools.encodeUserKey(name);
    String newAddress = generateMultiSigAddress(addresses, name);
    bitcoindRpc.importaddress(newAddress, internalName, true);

    return newAddress;
  }

  private static void scanForAddresses() {
    Map<String, BigDecimal> knownAccounts = bitcoindRpc.listaccounts(0, true);
    knownAccounts.keySet().forEach(account -> {
      // Look for any known PK/Single accounts and generate the matching multisig in memory
      Pattern pattern = Pattern.compile("^" + PUBKEY_PREFIX + "(.*)");
      Matcher matcher = pattern.matcher(account);
      if (matcher.matches()) {
        String pubKey = matcher.group(1);
        generateMultiSigAddress(Arrays.asList(new String[] {pubKey}), null);
      }
    });
  }

  private static String generateMultiSigAddress(Iterable<String> addresses, String name) {
    LinkedList<String> multisigAddresses = new LinkedList<>();
    addresses.forEach((address) -> {
      // Check if any of the addresses belong to the user
      int rounds = 1;
      String userPrivateKey =
          BitcoinTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);

      String userAddress = BitcoinTools.NOKEY;
      if (!userPrivateKey.equalsIgnoreCase(BitcoinTools.NOKEY)) {
        userAddress = BitcoinTools.getPublicAddress(userPrivateKey);

        while (!address.equalsIgnoreCase(userAddress)
            && rounds <= config.getMaxDeterministicAddresses()) {
          rounds++;
          userPrivateKey =
              BitcoinTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
          userAddress = BitcoinTools.getPublicAddress(userPrivateKey);
        }
      }

      if (address.equalsIgnoreCase(userAddress)) {
        multisigAddresses.add(BitcoinTools.getPublicKey(userPrivateKey));
      } else {
        multisigAddresses.add(address);
      }
    });

    for (String account : config.getMultiSigAccounts()) {
      if (!account.isEmpty()) {
        multisigAddresses.add(account);
      }
    }

    String[] addressArray = new String[multisigAddresses.size()];
    MultiSig newAddress = bitcoindRpc.createmultisig(config.getMinSignatures(),
        multisigAddresses.toArray(addressArray));
    if (name != null && !name.isEmpty()) {
      // Bitcoind refuses to connect the address it has to the p2sh script even when provided.
      // Simplest to just load it, it still doesn't have the private keys.
      bitcoindRpc.addmultisigaddress(config.getMinSignatures(),
          multisigAddresses.toArray(addressArray), BitcoinTools.encodeUserKey(name));
    }

    multiSigRedeemScripts.put(newAddress.getAddress(), newAddress.getRedeemScript());

    return newAddress.getAddress();
  }

  @Override
  public String getBalance(String address) {
    BigDecimal balance = BigDecimal.ZERO;
    Output[] outputs = bitcoindRpc.listunspent(config.getMinConfirmations(),
        config.getMaxConfirmations(), new String[] {address});
    for (Output output : outputs) {
      balance = balance.add(output.getAmount());
    }
    return balance.toPlainString();
  }

  @Override
  public String createTransaction(Iterable<String> fromAddress, Iterable<Recipient> toAddress) {
    List<String> fromAddresses = new LinkedList<>();
    fromAddress.forEach(fromAddresses::add);
    String[] addresses = new String[fromAddresses.size()];
    Outpoint[] outputs = bitcoindRpc.listunspent(config.getMinConfirmations(),
        config.getMaxConfirmations(), fromAddresses.toArray(addresses));

    List<Outpoint> usedOutputs = new LinkedList<>();
    Map<String, BigDecimal> txnOutput = new HashMap<>();
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal subTotal = BigDecimal.ZERO;
    Iterator<Recipient> recipients = toAddress.iterator();
    Recipient recipient = recipients.next();
    boolean filledAllOutputs = false;
    for (Outpoint output : outputs) {
      total = total.add(output.getAmount());
      subTotal = subTotal.add(output.getAmount());
      usedOutputs.add(output);

      if (subTotal.compareTo(recipient.getAmount()) > 0) {
        txnOutput.put(recipient.getRecipientAddress(), recipient.getAmount());
        subTotal = subTotal.subtract(recipient.getAmount());
        if (recipients.hasNext()) {
          recipient = recipients.next();
        } else {
          // TODO don't hardcode fees -- 0.0001 BTC * KB suggested by spec
          txnOutput.put(fromAddress.iterator().next(), subTotal.subtract(new BigDecimal("0.002")));
          filledAllOutputs = true;
          break;
        }
      }
    }

    // We don't have enough to complete the transaction
    if (!filledAllOutputs) {
      return null;
    }

    RawTransaction rawTx = new RawTransaction();
    rawTx.setVersion(1);
    rawTx.setInputCount(usedOutputs.size());
    usedOutputs.forEach((input) -> {
      RawInput rawInput = new RawInput();
      rawInput.setTxHash(input.getTransactionId());
      rawInput.setTxIndex((int) input.getOutputIndex());
      rawInput.setSequence(-1);
      rawTx.getInputs().add(rawInput);
    });
    rawTx.setOutputCount(txnOutput.size());
    txnOutput.forEach((address, amount) -> {
      RawOutput rawOutput = new RawOutput();
      rawOutput.setAmount(amount.multiply(BigDecimal.valueOf(100000000)).longValue());
      String decodedAddress = BitcoinTools.decodeAddress(address);
      byte[] addressBytes = ByteUtilities.toByteArray(decodedAddress);
      String scriptData = "";
      if (!BitcoinTools.isMultiSigAddress(address)) {
        // Regular address
        scriptData = "76a914";
        scriptData += ByteUtilities.toHexString(addressBytes);
        scriptData += "88ac";
      } else {
        // Multi-sig address
        scriptData = "a914";
        scriptData += ByteUtilities.toHexString(addressBytes);
        scriptData += "87";
      }
      rawOutput.setScript(scriptData);
      rawTx.getOutputs().add(rawOutput);
    });
    rawTx.setLockTime(0);

    return rawTx.encode();
  }

  @Override
  public String signTransaction(String transaction, String address) {
    return signTransaction(transaction, address, null);
  }

  @Override
  public String signTransaction(String transaction, String address, String name) {
    logger.debug("Attempting to sign a transaction");
    int rounds = 1;
    String privateKey = "";
    String userAddress = "";
    SignedTransaction signedTransaction = null;

    if (name != null) {
      logger.debug("User key has value, trying to determine private key");
      privateKey =
          BitcoinTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
      userAddress = BitcoinTools.getPublicAddress(privateKey);
      while (!userAddress.equalsIgnoreCase(address)
          && !generateMultiSigAddress(Arrays.asList(new String[] {userAddress}), name)
              .equalsIgnoreCase(address)
          && rounds < config.getMaxDeterministicAddresses()) {
        rounds++;
        privateKey =
            BitcoinTools.getDeterministicPrivateKey(name, config.getServerPrivateKey(), rounds);
        userAddress = BitcoinTools.getPublicAddress(privateKey);
      }

      // If we hit max addresses/user bail out
      if (!userAddress.equalsIgnoreCase(address)
          && !generateMultiSigAddress(Arrays.asList(new String[] {userAddress}), name)
              .equalsIgnoreCase(address)) {
        logger.debug("Too many rounds, failed to sign");
        return transaction;
      }

      logger.debug("We can sign for " + userAddress);

      // We have the private key, now get all the unspent inputs so we have the redeemScripts.
      Outpoint[] outputs = bitcoindRpc.listunspent(config.getMinConfirmations(),
          config.getMaxConfirmations(), new String[] {});

      RawTransaction rawTx = RawTransaction.parse(transaction);
      final byte[] addressData = BitcoinTools.getPublicKeyBytes(privateKey);
      final byte[] privateKeyBytes =
          ByteUtilities.toByteArray(BitcoinTools.decodeAddress(privateKey));
      rawTx.getInputs().forEach((input) -> {
        for (Outpoint output : outputs) {
          logger.debug("Looking for outputs we can sign");
          if (output.getTransactionId().equalsIgnoreCase(input.getTxHash())
              && output.getOutputIndex() == input.getTxIndex()) {
            OutpointDetails outpoint = new OutpointDetails();
            outpoint.setTransactionId(output.getTransactionId());
            outpoint.setOutputIndex(output.getOutputIndex());
            outpoint.setScriptPubKey(output.getScriptPubKey());
            outpoint.setRedeemScript(multiSigRedeemScripts.get(output.getAddress()));

            if (output.getAddress().equalsIgnoreCase(address)) {
              RawTransaction signingTx = RawTransaction.stripInputScripts(rawTx);
              byte[] sigData = new byte[] {};

              logger.debug("Found an output, matching to inputs in the transaction");
              for (RawInput sigInput : signingTx.getInputs()) {
                if (sigInput.getTxHash().equalsIgnoreCase(outpoint.getTransactionId())
                    && sigInput.getTxIndex() == outpoint.getOutputIndex()) {
                  // This is the input we're processing, fill it and sign it
                  if (BitcoinTools.isMultiSigAddress(address)) {
                    sigInput.setScript(outpoint.getRedeemScript());
                  } else {
                    sigInput.setScript(outpoint.getScriptPubKey());
                  }

                  byte[] hashTypeBytes =
                      ByteUtilities.stripLeadingNullBytes(BigInteger.valueOf(1).toByteArray());
                  hashTypeBytes = ByteUtilities.leftPad(hashTypeBytes, 4, (byte) 0x00);
                  hashTypeBytes = ByteUtilities.flipEndian(hashTypeBytes);
                  String sigString = signingTx.encode() + ByteUtilities.toHexString(hashTypeBytes);
                  logger.debug("Signing: " + sigString);

                  try {
                    sigData = ByteUtilities.toByteArray(sigString);
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    sigData = md.digest(md.digest(sigData));
                  } catch (Exception e) {
                    StringWriter errors = new StringWriter();
                    e.printStackTrace(new PrintWriter(errors));
                    logger.error(errors.toString());
                  }

                  byte[][] sigResults = Secp256k1.signTransaction(sigData, privateKeyBytes);
                  StringBuilder signature = new StringBuilder();
                  // Only want R & S, don't need V
                  for (int i = 0; i < 2; i++) {
                    byte[] sig = sigResults[i];
                    signature.append("02");
                    byte[] sigBytes = sig;
                    byte[] sigSize = BigInteger.valueOf(sigBytes.length).toByteArray();
                    sigSize = ByteUtilities.stripLeadingNullBytes(sigSize);
                    signature.append(ByteUtilities.toHexString(sigSize));
                    signature.append(ByteUtilities.toHexString(sigBytes));
                  }

                  byte[] sigBytes = ByteUtilities.toByteArray(signature.toString());
                  byte[] sigSize = BigInteger.valueOf(sigBytes.length).toByteArray();
                  sigSize = ByteUtilities.stripLeadingNullBytes(sigSize);
                  String signatureString =
                      ByteUtilities.toHexString(sigSize) + signature.toString();
                  signatureString = "30" + signatureString;

                  sigData = ByteUtilities.toByteArray(signatureString);
                  break;
                }
              }

              // Determine how we need to format the sig data
              if (BitcoinTools.isMultiSigAddress(address)) {
                for (RawInput signedInput : rawTx.getInputs()) {
                  if (signedInput.getTxHash().equalsIgnoreCase(outpoint.getTransactionId())
                      && signedInput.getTxIndex() == outpoint.getOutputIndex()) {

                    // Merge the new signature with existing ones.
                    signedInput.stripMultiSigRedeemScript(outpoint.getRedeemScript());

                    String scriptData = signedInput.getScript();
                    if (scriptData.isEmpty()) {
                      scriptData += "00";
                    }

                    byte[] dataSize = RawTransaction.writeVariableStackInt(sigData.length + 1);
                    scriptData += ByteUtilities.toHexString(dataSize);
                    scriptData += ByteUtilities.toHexString(sigData);
                    scriptData += "01";

                    byte[] redeemScriptBytes =
                        ByteUtilities.toByteArray(outpoint.getRedeemScript());
                    dataSize = RawTransaction.writeVariableStackInt(redeemScriptBytes.length);
                    scriptData += ByteUtilities.toHexString(dataSize);
                    scriptData += ByteUtilities.toHexString(redeemScriptBytes);

                    signedInput.setScript(scriptData);
                    break;
                  }
                }
              } else {
                for (RawInput signedInput : rawTx.getInputs()) {
                  if (signedInput.getTxHash().equalsIgnoreCase(outpoint.getTransactionId())
                      && signedInput.getTxIndex() == outpoint.getOutputIndex()) {

                    // Sig then pubkey
                    String scriptData = "";
                    byte[] dataSize = new byte[] {};

                    dataSize = RawTransaction.writeVariableStackInt(sigData.length + 1);
                    scriptData += ByteUtilities.toHexString(dataSize);
                    scriptData += ByteUtilities.toHexString(sigData);
                    scriptData += "01"; // SIGHASH.ALL

                    dataSize = RawTransaction.writeVariableStackInt(addressData.length);
                    scriptData += ByteUtilities.toHexString(dataSize);
                    scriptData += ByteUtilities.toHexString(addressData);

                    signedInput.setScript(scriptData);
                    break;
                  }
                }
              }
            }
          }
        }
      });
      signedTransaction = new SignedTransaction();
      signedTransaction.setTransaction(rawTx.encode());
    } else {
      signedTransaction =
          bitcoindRpc.signrawtransaction(transaction, new OutpointDetails[] {}, null, SigHash.ALL);
    }

    return signedTransaction.getTransaction();
  }

  @Override
  public String sendTransaction(String transaction) {
    return bitcoindRpc.sendrawtransaction(transaction, false);
  }

  @Override
  public TransactionDetails[] getTransactions(String address, int numberToReturn, int skipNumber) {
    LinkedList<TransactionDetails> txDetails = new LinkedList<>();
    Payment[] payments = bitcoindRpc.listtransactions("*", 1000000, 0, true);
    Arrays.asList(payments).forEach(payment -> {

      // Lookup the txid and vout/vin based on the sign of the amount (+/-)
      // Determine the address involved
      try {
        String rawTx = bitcoindRpc.getrawtransaction(payment.getTxid());
        RawTransaction tx = RawTransaction.parse(rawTx);
        if (payment.getCategory() == PaymentCategory.receive) {

          // Paid to the account
          if (payment.getAddress().equalsIgnoreCase(address)) {
            TransactionDetails detail = new TransactionDetails();
            detail.setAmount(payment.getAmount());

            // Senders
            HashSet<String> senders = new HashSet<>();
            tx.getInputs().forEach(input -> {
              try {
                String rawSenderTx = bitcoindRpc.getrawtransaction(input.getTxHash());
                RawTransaction senderTx = RawTransaction.parse(rawSenderTx);
                String script = senderTx.getOutputs().get(input.getTxIndex()).getScript();
                String scriptAddress = RawTransaction.decodeRedeemScript(script);
                senders.add(scriptAddress);
              } catch (Exception e) {
                senders.add(null);
              }
            });
            detail.setFromAddress(senders.toArray(new String[] {}));

            detail.setToAddress(new String[] {address});
            detail.setTxHash(payment.getTxid());

            txDetails.add(detail);
          }
        } else if (payment.getCategory() == PaymentCategory.send) {
          // Sent from the account
          tx.getInputs().forEach(input -> {
            String rawSenderTx = bitcoindRpc.getrawtransaction(input.getTxHash());
            RawTransaction senderTx = RawTransaction.parse(rawSenderTx);
            String script = senderTx.getOutputs().get(input.getTxIndex()).getScript();
            String scriptAddress = RawTransaction.decodeRedeemScript(script);

            if (scriptAddress.equalsIgnoreCase(address)) {
              TransactionDetails detail = new TransactionDetails();

              detail.setTxHash(payment.getTxid());
              detail.setAmount(payment.getAmount());
              detail.setFromAddress(new String[] {address});
              detail.setToAddress(new String[] {payment.getAddress()});

              txDetails.add(detail);
            }
          });
        }
      } catch (Exception e) {
        StringWriter errors = new StringWriter();
        e.printStackTrace(new PrintWriter(errors));
        logger.debug(errors.toString());
      }
    });

    LinkedList<TransactionDetails> removeThese = new LinkedList<>();
    txDetails.forEach(detail -> {
      boolean noMatch = false;
      for (String from : Arrays.asList(detail.getFromAddress())) {
        boolean subMatch = false;
        for (String to : Arrays.asList(detail.getToAddress())) {
          if (to.equalsIgnoreCase(from)) {
            subMatch = true;
            break;
          }
        }
        if (!subMatch) {
          noMatch = true;
          break;
        }
      }

      // If the from & to's match then it's just a return amount, simpler if we don't list it.
      if (!noMatch) {
        removeThese.add(detail);
      }
    });

    removeThese.forEach(detail -> {
      txDetails.remove(detail);
    });
    return txDetails.toArray(new TransactionDetails[] {});
  }
}