package de.robv.android.xposed;
public class XSharedPreferences {
  public XSharedPreferences(String pkg, String pref){}
  public boolean getBoolean(String key, boolean def){return def;}
  public String getString(String key, String def){return def;}
  public int getInt(String key, int def){return def;}
  public long getLong(String key, long def){return def;}
  public void reload(){}
  public boolean makeWorldReadable(){return true;}
  public java.io.File getFile(){return null;}
}
