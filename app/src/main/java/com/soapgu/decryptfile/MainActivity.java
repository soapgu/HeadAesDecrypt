package com.soapgu.decryptfile;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.TextView;

import com.orhanobut.logger.Logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MainActivity extends AppCompatActivity {

    OkHttpClient client;
    TextView msg;
    private final CompositeDisposable disposable = new CompositeDisposable();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        client = new OkHttpClient();
        setContentView(R.layout.activity_main);
        msg = findViewById(R.id.msg);
        findViewById(R.id.btn_start).setOnClickListener( v -> {
            Request request = new Request.Builder()
                    .url("http://testing.heyshare.cn/download/encrypt_sample.jpg")
                    .get()
                    .build();
            Call call = client.newCall(request);
            String filePath = getExternalFilesDir(null) + "/image.jpg";
            disposable.add( downloadFile(call)
                            .flatMap( body -> decryptFile(body,filePath) )
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe( file ->{
                        msg.setText( file.getName() );
                        //body.close();
                    },
                    throwable -> Logger.e(throwable,"download error")));
        } );
    }

    private Single<ResponseBody> downloadFile( Call call ){
        return Single.create(subscriber -> {
            Logger.i( "------Begin To Request Http Thread:%s",  Thread.currentThread().getId() );
            call.enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Logger.e(e,"------- Http Error:%s",  e.getMessage() );
                    subscriber.onError( e );
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    subscriber.onSuccess( response.body() );
                }
            });
        });
    }

    private Single<File> decryptFile( ResponseBody body, String filePath ){
        return Single.create( emitter -> {
            File file = new File(filePath);
            int headLength = 1024;
            if( body.contentLength() > 2048 ){
                headLength += 16;
            }
            byte[] header = new byte[headLength];
            byte[] headerBuffer = new byte[headLength];
            byte[] buffer = new byte[4096];
            InputStream inputStream = body.byteStream();
            OutputStream outputStream = null;
            try {
                long totalProcess = 0L;
                outputStream = new FileOutputStream(file);
                long skip = inputStream.skip(1024);
                totalProcess += skip;
                Logger.i( "Step 1: skip %d",skip );
                int read =  inputStream.read( headerBuffer );
                if( read == headLength ){
                    header = headerBuffer;
                } else {
                    System.arraycopy(headerBuffer, 0, header, 0, read);
                    headerBuffer = new byte[headLength - read];
                    int remain =  inputStream.read( headerBuffer );
                    Logger.i("read first %s, remain header expect:%s,actual %s", read,headLength - read,remain);
                    System.arraycopy(headerBuffer,0,header,read,remain);
                }
                Logger.i( "Step 2: read header length %d",read );
                totalProcess += read;
                byte[] decryptedBytes = decryptHeader( header );
                Logger.i( "Step 3: decrypt header length %d",decryptedBytes.length );
                Logger.i("decrypt content:%s",HexUtils.bytesToHex(decryptedBytes));
                outputStream.write(decryptedBytes, 0, decryptedBytes.length);
                do{
                    read = inputStream.read(buffer);
                    //Logger.i( "read file length:%d",read );
                    if( read > 0 ){
                        totalProcess += read;
                        outputStream.write( buffer,0,read );
                    }
                }while (read > 0);
                Logger.i( "process file content %s / %s ",totalProcess,body.contentLength() );
                outputStream.flush();
                emitter.onSuccess( new File(filePath) );
            } catch (Exception e) {
                emitter.onError(e);
            }
            finally {
                if (inputStream != null) {
                    inputStream.close();
                }
                if (outputStream != null) {
                    outputStream.close();
                }
                body.close();
            }
        } );

    }

    private byte[] decryptHeader( byte[] bytes ){
        String ALGORITHM = "AES/CBC/PKCS7Padding";
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            SecretKey secretKey = new SecretKeySpec( HexUtils.hexStringToByteArray( "30980f98296b77f00a55f3c92b35322d898ae2ffcdb906de40336d2cf3d556a0" ) , "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(HexUtils.hexStringToByteArray("e5889166bb98ba01e1a6bc9b32dbf3e6"));
            Logger.i("---Begin decrypt----");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
            return cipher.doFinal(bytes);
        } catch (InvalidAlgorithmParameterException | BadPaddingException | InvalidKeyException |
                 NoSuchAlgorithmException | IllegalBlockSizeException | NoSuchPaddingException e) {
            throw new RuntimeException(e);
        }

    }

}