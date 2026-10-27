package com.liskovsoft.smartyoutubetv2.common.vot;

import android.content.Context;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.*;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.Assume;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Live integration is opt-in: run with instrumentation argument liveSabr=true. */
@RunWith(AndroidJUnit4.class)
public class VotSabrIntegrationTest {
    @Test public void downloadAndUploadOriginalAudio() throws Exception {
        Assume.assumeTrue("Live network integration requires explicit opt-in",
                "true".equals(InstrumentationRegistry.getArguments().getString("liveSabr")));
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String videoId=InstrumentationRegistry.getArguments().getString("videoId","arj7oStGLkU");
        String url="https://www.youtube.com/watch?v="+videoId;
        File audio=new NativeSabrAudioDownloader(context,()->false).download(videoId,"en",null);
        try {
            assertTrue("Audio stream should contain actual media", audio.length()>10000);
            File qa=new File(context.getCacheDir(),"vot-sabr-qa.audio");
            try(FileInputStream in=new FileInputStream(audio);FileOutputStream out=new FileOutputStream(qa)) {
                byte[] b=new byte[64*1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
            }
            VotClient client=new VotClient(context);
            Method request=VotClient.class.getDeclaredMethod("requestTranslation",String.class,double.class,String.class,boolean.class);
            request.setAccessible(true);
            VotTranslationResponse response=(VotTranslationResponse)request.invoke(client,url,844d,"Inside the Mind of a Master Procrastinator",true);
            assertNotNull("Yandex should return a translation ID for upload",response.translationId);
            assertFalse(response.translationId.isEmpty());
            Log.i("VotSabrQA","source bytes="+audio.length()+" initialStatus="+response.status);
            Method upload=VotClient.class.getDeclaredMethod("uploadSabrAudio",String.class,String.class,File.class);
            upload.setAccessible(true);upload.invoke(client,url,response.translationId,audio);
            VotTranslationResponse after=(VotTranslationResponse)request.invoke(client,url,844d,"",true);
            assertTrue("Translation should remain valid after a real audio upload",after.isReady()||after.isWaiting());
            Log.i("VotSabrQA","PASS source downloaded, real audio accepted, translationStatus="+after.status);
        } finally { audio.delete(); }
    }
}
