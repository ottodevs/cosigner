package io.emax.cosigner.bitcoin.bitcoindrpc;

public enum ScriptPubKeyType {
  /** P2PK script */
  pubkey, /** P2PKH script */
  pubkeyhash, /** P2SH script */
  scripthash, /** a bare multisig script */
  multisig, /** nulldata scripts */
  nulldata, /** unknown scripts */
  nonstandard,
}