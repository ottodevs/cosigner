package io.emax.heimdal.ethereum.gethrpc;

public class CallData {
  private String to;
  private String from;
  private String value;
  private String gas;
  private String gasprice;
  private String data;

  public String getTo() {
    return to;
  }

  public void setTo(String to) {
    this.to = to;
  }

  public String getFrom() {
    return from;
  }

  public void setFrom(String from) {
    this.from = from;
  }

  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }

  public String getGas() {
    return gas;
  }

  public void setGas(String gas) {
    this.gas = gas;
  }

  public String getGasprice() {
    return gasprice;
  }

  public void setGasprice(String gasprice) {
    this.gasprice = gasprice;
  }

  public String getData() {
    return data;
  }

  public void setData(String data) {
    this.data = data;
  }
}
