package com.soapgu.decryptfile;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.ProgressBar;
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
import java.text.DecimalFormat;
import java.util.Date;

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
    TextView msg,tv_complete;
    ProgressBar progressBar;
    private final CompositeDisposable disposable = new CompositeDisposable();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        client = new OkHttpClient();
        setContentView(R.layout.activity_main);
        msg = findViewById(R.id.msg);
        tv_complete = findViewById(R.id.tv_complete);
        progressBar = findViewById(R.id.progressBar);
        progressBar.setMax(100);

        findViewById(R.id.btn_small_start).setOnClickListener( v -> {
            productFile("encrypt_sample.jpg");
        } );

        findViewById(R.id.btn_medium_start).setOnClickListener( v-> {
            productFile( "encrypt_1G.mp4" );
        } );

        findViewById(R.id.btn_big_start).setOnClickListener( v-> {
            productFile( "encrypt_film.mkv" );
        } );
    }

    private void productFile( String fileName ){
        tv_complete.setText("");
        Date start = new Date();
        String url = String.format("http://testing.heyshare.cn/download/%s",fileName);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        Call call = client.newCall(request);
        String filePath = getExternalFilesDir(null) + "/" + fileName;
        disposable.add( downloadFile(call)
                .flatMap( body -> decryptFile(body,filePath) )
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe( file ->{
                            msg.setText( String.format( "文件%s解密完成",fileName ) );
                            Date end = new Date();
                            Long diff = end.getTime() - start.getTime() ;
                            tv_complete.setText( String.format( "用时 %s 毫秒", diff ));
                        },
                        throwable -> Logger.e(throwable,"download error")));
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

            byte[] buffer = new byte[4096];
            InputStream inputStream = body.byteStream();
            OutputStream outputStream = null;
            try {
                this.runOnUiThread( ()-> progressBar.setProgress( 0 ));
                long totalProcess = 0L;
                outputStream = new FileOutputStream(file);
                long skip = inputStream.skip(1024);
                totalProcess += skip;
                Logger.i( "Step 1: skip %d",skip );
                byte[] header = readHeader( inputStream , headLength );
                Logger.i( "Step 2: read header length %d",headLength );
                totalProcess += headLength;
                byte[] decryptedBytes = decryptHeader( header );
                Logger.i( "Step 3: decrypt header length %d",decryptedBytes.length );
                //Logger.i("decrypt content:%s",HexUtils.bytesToHex(decryptedBytes));
                outputStream.write(decryptedBytes, 0, decryptedBytes.length);
                int read;
                long contentLength = body.contentLength();
                long current = 0;
                DecimalFormat df = new DecimalFormat("#.##");
                do{
                    current ++;
                    read = inputStream.read(buffer);
                    //Logger.i( "read file length:%d",read );
                    if( read > 0 ){
                        totalProcess += read;
                        outputStream.write( buffer,0,read );
                        if( current % 10 == 0 ){
                            double percent = totalProcess * 100.00  / contentLength;
                            this.runOnUiThread( ()->{
                                msg.setText( String.format("%s %%",df.format( percent )) );
                                if( (int)percent > progressBar.getProgress() ) {
                                    progressBar.setProgress((int)percent);
                                }
                            });
                        }
                    }
                }while (read > 0);
                Logger.i( "process file content %s / %s ",totalProcess,contentLength );
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

    /**
     * 保证读取指定长度数据
     * @param inputStream 输入流
     * @param headLength 长度
     * @return 数据数组
     * @throws IOException
     */
    private byte[] readHeader( InputStream inputStream, int headLength ) throws IOException {
        byte[] header = new byte[headLength];

        int total = 0;
        int remain = headLength;
        do {
            byte[] headerBuffer = new byte[remain];
            int read = inputStream.read(headerBuffer);
            System.arraycopy(headerBuffer,0,header,total,read);
            total += read;
            remain = headLength - total;
            Logger.i("current read head %d / %d , remain %d", total, headLength,remain );
        } while (remain > 0);
        return header;
    }

    /**
     * AES 解密
     * 使用CBC模式&PKCS7Padding
     * @param bytes 密文
     * @return 明文
     */
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