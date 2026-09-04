package com.noop.mgecg;

import android.Manifest; import android.app.Activity; import android.bluetooth.*; import android.bluetooth.le.*; import android.content.*; import android.content.pm.PackageManager; import android.os.*; import android.view.*; import android.widget.*; import java.util.*; import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ=42;
    private BluetoothAdapter adapter; private BluetoothGatt gatt; private BluetoothGattCharacteristic cmdWrite; private int seq=1;
    private BluetoothLeScanner scanner;
    private BluetoothDevice pendingDevice;
    private TextView log; private Button scanBtn; private EditText customInput; private ScrollView scrollView;
    private final UUID svc=UUID.fromString(Protocol.SERVICE), cmd=UUID.fromString(Protocol.CMD_WRITE), cmdN=UUID.fromString(Protocol.CMD_NOTIFY), dataN=UUID.fromString(Protocol.DATA_NOTIFY), eventN=UUID.fromString(Protocol.EVENT_NOTIFY), extraN=UUID.fromString(Protocol.EXTRA_NOTIFY);

    private final ArrayDeque<Runnable> opQueue = new ArrayDeque<>();
    private boolean opInFlight = false;
    private final Handler mainH = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private void enqueue(Runnable op){ opQueue.add(op); drainQueue(); }
    private void drainQueue(){ if(opInFlight || opQueue.isEmpty()) return; opInFlight=true; timeoutRunnable=()->{ line("TIMEOUT - no callback, unsticking queue"); timeoutRunnable=null; opInFlight=false; drainQueue(); }; mainH.postDelayed(timeoutRunnable,4000); opQueue.poll().run(); }
    private void opDone(){ if(timeoutRunnable!=null){ mainH.removeCallbacks(timeoutRunnable); timeoutRunnable=null; } opInFlight=false; drainQueue(); }

    private final BroadcastReceiver bondReceiver = new BroadcastReceiver(){
        @Override public void onReceive(Context ctx, Intent i){
            if(!BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(i.getAction())) return;
            BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1);
            String label = state==BluetoothDevice.BOND_BONDED?"BONDED":state==BluetoothDevice.BOND_BONDING?"BONDING":state==BluetoothDevice.BOND_NONE?"BOND_NONE":String.valueOf(state);
            line("BOND STATE "+label+" ("+(d!=null?d.getAddress():"?")+")");
            if(state==BluetoothDevice.BOND_BONDED && pendingDevice!=null && d!=null && d.getAddress().equals(pendingDevice.getAddress()) && gatt==null){
                BluetoothDevice toConnect=pendingDevice; pendingDevice=null;
                line("CONNECTING (post-bond) "+toConnect.getAddress());
                gatt=toConnect.connectGatt(MainActivity.this,false,cb,BluetoothDevice.TRANSPORT_LE);
            } else if(state==BluetoothDevice.BOND_NONE && pendingDevice!=null){
                line("BONDING FAILED/CANCELLED - not connecting"); pendingDevice=null;
            }
        }
    };

    private final BluetoothGattCallback cb=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int status,int state){ line("GATT state="+state+" status="+status);
            if(state==BluetoothProfile.STATE_CONNECTED){ g.discoverServices(); }
            else if(state==BluetoothProfile.STATE_DISCONNECTED){ try{g.close();}catch(Exception ignored){} if(timeoutRunnable!=null){mainH.removeCallbacks(timeoutRunnable);timeoutRunnable=null;} gatt=null; cmdWrite=null; opQueue.clear(); opInFlight=false; } }
        @Override public void onServicesDiscovered(BluetoothGatt g,int status){ line("services discovered status="+status); BluetoothGattService s=g.getService(svc); if(s==null){line("ERROR: fd4b service not found");return;} cmdWrite=s.getCharacteristic(cmd);
            subscribe(g,s.getCharacteristic(cmdN)); subscribe(g,s.getCharacteristic(eventN)); subscribe(g,s.getCharacteristic(dataN)); subscribe(g,s.getCharacteristic(extraN));
            if(cmdWrite!=null){ enqueue(()->{ line("TX CLIENT_HELLO (confirmed write)"); cmdWrite.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); cmdWrite.setValue(Protocol.clientHello()); if(!g.writeCharacteristic(cmdWrite)){line("writeCharacteristic() rejected (CLIENT_HELLO)");opDone();} }); } }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int status){ line("WRITE " + c.getUuid()+" status="+status); opDone(); }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int status){ line("CCCD "+shortUuid(d.getCharacteristic().getUuid())+" status="+status); opDone(); }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){
            line("RX "+shortUuid(c.getUuid())+"  "+Protocol.hex(value)+"  "+Protocol.frameSummary(value));
            scanForTimestamps(value);
            if(value.length>=11 && (value[0]&0xff)==0xAA){
                int frType=value[8]&0xff, frCmd=value[10]&0xff;
                if("0004".equals(shortUuid(c.getUuid())) && frType==48 && frCmd==0x1D){
                    line("Recording-complete event matched -> auto-firing guessed PULL (type 0x2F cmd 0x01 arg 0x00)");
                    sendCustom(0x2F,0x01,0x00);
                }
            }
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){ byte[] v=c.getValue(); onCharacteristicChanged(g,c,v); }
    };
    private String shortUuid(UUID u){return u.toString().substring(4,8);}
    private void subscribe(BluetoothGatt g,BluetoothGattCharacteristic c){ if(c==null)return; if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;
        enqueue(()->{ g.setCharacteristicNotification(c,true); BluetoothGattDescriptor d=c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")); if(d!=null){d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); if(!g.writeDescriptor(d)){line("writeDescriptor() rejected "+shortUuid(c.getUuid()));opDone();}}else{opDone();} line("subscribe "+shortUuid(c.getUuid())); });
    }
    private void send(int opcode,int arg,String name){ sendNamed(0x23,opcode,arg,name); }
    private void sendNamed(int type,int opcode,int arg,String name){ if(gatt==null||cmdWrite==null){line("NOT CONNECTED");return;} enqueue(()->{ byte[] f=Protocol.labrador(type,opcode,arg,seq++); line("TX "+name+"  "+Protocol.hex(f)); cmdWrite.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); cmdWrite.setValue(f); if(!gatt.writeCharacteristic(cmdWrite)){line("writeCharacteristic() rejected ("+name+")");opDone();} }); }
    private void sendCustom(int type,int opcode,int arg){ sendNamed(type,opcode,arg,String.format("CUSTOM type=0x%02X cmd=0x%02X arg=0x%02X",type,opcode,arg)); }
    private void scanForTimestamps(byte[] value){
        long now = System.currentTimeMillis()/1000L;
        long lo = now - 7L*86400L, hi = now + 7L*86400L;
        for(int i=0;i+4<=value.length;i++){
            long v=Protocol.u32le(value,i);
            if(v>lo && v<hi){ line(String.format("  ts-candidate @%d: %d (%s)",i,v,new Date(v*1000L))); }
        }
    }
    @Override protected void onCreate(Bundle b){super.onCreate(b); adapter=((BluetoothManager)getSystemService(BLUETOOTH_SERVICE)).getAdapter(); buildUi(); requestPerms(); registerReceiver(bondReceiver,new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)); }
    private void buildUi(){ LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,24,24,24); TextView title=new TextView(this); title.setText("NOOP MG ECG Experimental\nWHOOP 5/MG Labrador command probe"); title.setTextSize(20); root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        scanBtn=new Button(this);scanBtn.setText("SCAN FOR WHOOP 5/MG");scanBtn.setOnClickListener(v->scan());root.addView(scanBtn);
        Button c1=btn("0x7B  SELECT_WRIST (RIGHT=0)",v->send(0x7B,0,"SELECT_WRIST")); root.addView(c1);
        Button c4=btn("0x8B  FILTERED ON",v->send(0x8B,1,"FILTERED_ON"));root.addView(c4);
        Button c3=btn("0x7D  RAW SAVE ON",v->send(0x7D,1,"RAW_SAVE_ON"));root.addView(c3);
        Button c2=btn("0x7C  LABRADOR GENERATION START",v->send(0x7C,1,"LABRADOR_START"));root.addView(c2);
        Button c5=btn("0x3F  SPO2 STREAM ON",v->send(0x3F,1,"SPO2_ON"));root.addView(c5);
        Button stop=btn("0x7C  LABRADOR STOP",v->send(0x7C,0,"LABRADOR_STOP"));root.addView(stop);
        customInput=new EditText(this); customInput.setHint("type cmd arg hex, e.g. 2F 01 00"); customInput.setSingleLine(true); root.addView(customInput);
        Button sendCustomBtn=btn("SEND CUSTOM FRAME",v->{
            String[] parts=customInput.getText().toString().trim().split("\\s+");
            if(parts.length!=3){ line("custom frame needs exactly 3 hex bytes: type cmd arg"); return; }
            try{ int t=Integer.parseInt(parts[0],16), cv=Integer.parseInt(parts[1],16), av=Integer.parseInt(parts[2],16); sendCustom(t,cv,av); }
            catch(Exception e){ line("parse error: "+e.getMessage()); }
        }); root.addView(sendCustomBtn);
        log=new TextView(this);log.setTextIsSelectable(true);log.setTextSize(11); scrollView=new ScrollView(this);scrollView.addView(log);root.addView(scrollView,new LinearLayout.LayoutParams(-1,0,1));setContentView(root); }
    private Button btn(String t,View.OnClickListener l){Button b=new Button(this);b.setText(t);b.setOnClickListener(l);return b;}
    private void requestPerms(){ if(Build.VERSION.SDK_INT>=31){ArrayList<String> p=new ArrayList<>();if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_SCAN);if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.BLUETOOTH_CONNECT);if(!p.isEmpty())requestPermissions(p.toArray(new String[0]),REQ);} }
    private void stopScanning(){ if(scanner!=null){ try{scanner.stopScan(sc);}catch(Exception ignored){} scanner=null; line("SCAN STOP"); } }
    private void scan(){ if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED){requestPerms();return;} scanner=adapter.getBluetoothLeScanner(); line("SCANNING 10s..."); ScanFilter f=new ScanFilter.Builder().setServiceUuid(new android.os.ParcelUuid(svc)).build(); ScanSettings ss=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(); scanner.startScan(Collections.singletonList(f),ss,sc);new Handler(Looper.getMainLooper()).postDelayed(this::stopScanning,10000); }
    private final ScanCallback sc=new ScanCallback(){@Override public void onScanResult(int type,ScanResult r){BluetoothDevice d=r.getDevice();line("FOUND "+d.getName()+" "+d.getAddress()+" RSSI="+r.getRssi());
        if(gatt==null && pendingDevice==null){
            if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)return;
            stopScanning();
            if(d.getBondState()==BluetoothDevice.BOND_BONDED){ line("ALREADY BONDED, CONNECTING "+d.getAddress()); gatt=d.connectGatt(MainActivity.this,false,cb,BluetoothDevice.TRANSPORT_LE); }
            else { line("NOT BONDED - requesting bond "+d.getAddress()); pendingDevice=d; boolean started=d.createBond(); if(!started){ line("createBond() returned false"); pendingDevice=null; } }
        }}};
    private void line(String s){runOnUiThread(()->{String old=log==null?"":log.getText().toString(); if(old.length()>12000)old=old.substring(old.length()-9000); if(log!=null)log.setText(old+String.format("\n%tT  %s",new Date(),s)); if(scrollView!=null) scrollView.post(()->scrollView.fullScroll(View.FOCUS_DOWN)); });}
    @Override protected void onDestroy(){ try{unregisterReceiver(bondReceiver);}catch(Exception ignored){} try{if(gatt!=null)gatt.close();}catch(Exception ignored){}super.onDestroy();}
}
