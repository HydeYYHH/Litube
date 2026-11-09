@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hhst.youtubelite.shorts

import android.os.SystemClock
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hhst.youtubelite.core.TestJson
import com.hhst.youtubelite.extractor.*
import java.io.*
import java.net.*
import javax.net.ssl.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.*
import org.koin.core.context.GlobalContext

/** TLS wire timing only: never saves packet payload, hostnames, signed URLs or credentials. */
class MediaTlsProbeAndroidTest {
    @get:Rule val activity=ActivityScenarioRule(ExtractionTestActivity::class.java)
    @Test fun compareHandshakes()=runBlocking(Dispatchers.IO) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("network")=="1")
        val output=File(instrumentation.targetContext.filesDir,"media-tls-probe.json");output.delete()
        val hosts=linkedMapOf("control" to "www.youtube.com","master" to "manifest.googlevideo.com")
        var setupFailure:List<String>?=null
        try {
            val extraction=GlobalContext.get().get<Extractor>().extract(args.getString("videoId")?:"bQwjr3kb_Ec",demand=PlaybackDemand(854,null,false,setOf("avc")))
            val stream=extraction.stream.await()
            stream.formats.firstOrNull{it.videoOnly}?.url?.toHttpUrl()?.host?.let{hosts["media"]=it}
        }catch(failure:Exception){setupFailure=causes(failure)}
        val rows=mutableListOf<Map<String,Any?>>()
        fun save(){output.writeText(TestJson.any(mapOf("setupFailure" to setupFailure,"rows" to rows)))}
        val rounds=args.getString("rounds")?.toInt()?:4
        for(round in 0 until rounds) for((kind,host) in hosts) {
            val addresses=try{InetAddress.getAllByName(host).toList()}catch(failure:IOException){
                rows+=mapOf("kind" to kind,"round" to round,"stage" to "dns","failure" to causes(failure));save();continue
            }
            val modes=if(round%2==0)listOf("default","tls12","http1","tls13") else listOf("tls13","http1","tls12","default")
            for(mode in modes) {
                val address=addresses.first()
                val began=SystemClock.elapsedRealtime()
                val wire=WireSocket(Socket(),began)
                var secure:SSLSocket?=null;var failure:List<String>?=null;var connected:Long?=null;var handshake:Long?=null
                var protocol:String?=null;var alpn:String?=null;var stage="tcp"
                try {
                    wire.connect(InetSocketAddress(address,443),4000);connected=SystemClock.elapsedRealtime()-began
                    wire.soTimeout=5500;stage="create_tls"
                    val context=SSLContext.getInstance("TLS").apply{init(null,null,null)}
                    secure=context.socketFactory.createSocket(wire,host,443,true) as SSLSocket
                    secure.soTimeout=5500
                    secure.enabledProtocols=when(mode){"tls12"->arrayOf("TLSv1.2");"tls13"->arrayOf("TLSv1.3");else->arrayOf("TLSv1.3","TLSv1.2")}
                    secure.sslParameters=secure.sslParameters.apply{
                        endpointIdentificationAlgorithm="HTTPS"
                        serverNames=listOf(SNIHostName(host))
                        applicationProtocols=if(mode=="http1")arrayOf("http/1.1")else arrayOf("h2","http/1.1")
                    }
                    stage="handshake";secure.startHandshake();handshake=SystemClock.elapsedRealtime()-began-connected
                    protocol=secure.session.protocol;alpn=secure.applicationProtocol;stage="success"
                }catch(error:Exception){failure=causes(error)}
                val elapsed=SystemClock.elapsedRealtime()-began
                val observed=wire.snapshot()
                try{secure?.close()}finally{wire.close()}
                rows+=mapOf("kind" to kind,"hostHash" to host.hashCode(),"round" to round,"mode" to mode,
                    "ipv6" to (address is Inet6Address),"dnsCount" to addresses.size,"localAddress" to address.isSiteLocalAddress,
                    "fakeAddress" to (address.address.size==4 && address.address[0].toInt().and(255)==198 && address.address[1].toInt().and(255) in 18..19),
                    "stage" to stage,"tcpMs" to connected,"handshakeMs" to handshake,"elapsedMs" to elapsed,
                    "protocol" to protocol,"alpn" to alpn,"failure" to failure,"wire" to observed)
                save()
            }
        }
        Assert.assertTrue(rows.isNotEmpty())
    }
    private fun causes(error:Throwable)=generateSequence(error){it.cause}.take(8).map{it.javaClass.name}.toList()

    internal class WireSocket(private val delegate:Socket,private val began:Long,
                              private val event:((String,Map<String,Any?>)->Unit)?=null):Socket() {
        private var incoming=0L;private var outgoing=0L;private var firstIn:Long?=null;private var firstOut:Long?=null
        private var eof:Long?=null;private var closedAt:Long?=null;private val reads=mutableListOf<Map<String,Any>>()
        fun snapshot()=mapOf("incomingBytes" to incoming,"outgoingBytes" to outgoing,"firstIncomingMs" to firstIn,
            "firstOutgoingMs" to firstOut,"eofMs" to eof,"closeMs" to closedAt,"reads" to reads.toList())
        private fun now()=SystemClock.elapsedRealtime()-began
        override fun getInputStream():InputStream=object:FilterInputStream(delegate.getInputStream()) {
            override fun read(bytes:ByteArray,offset:Int,length:Int):Int {
                val count=`in`.read(bytes,offset,length)
                if(count>0){incoming+=count;if(firstIn==null){firstIn=now();event?.invoke("wire_first_in",mapOf("bytes" to count,"recordType" to bytes[offset].toInt().and(255)))};if(reads.size<12)reads+=mapOf("ms" to now(),"bytes" to count,"firstByte" to bytes[offset].toInt().and(255))}
                else if(count<0){eof=now();event?.invoke("wire_eof",snapshot())}
                return count
            }
            override fun read():Int {val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt().and(255)}
        }
        override fun getOutputStream():OutputStream=object:FilterOutputStream(delegate.getOutputStream()) {
            override fun write(bytes:ByteArray,offset:Int,length:Int){out.write(bytes,offset,length);outgoing+=length;if(firstOut==null)firstOut=now()}
            override fun write(value:Int){out.write(value);outgoing++;if(firstOut==null)firstOut=now()}
        }
        override fun connect(endpoint:SocketAddress?,timeout:Int)=delegate.connect(endpoint,timeout)
        override fun connect(endpoint:SocketAddress?)=delegate.connect(endpoint)
        override fun getInetAddress():InetAddress?=delegate.inetAddress
        override fun getLocalAddress():InetAddress=delegate.localAddress
        override fun getPort()=delegate.port
        override fun getLocalPort()=delegate.localPort
        override fun getRemoteSocketAddress():SocketAddress?=delegate.remoteSocketAddress
        override fun getLocalSocketAddress():SocketAddress?=delegate.localSocketAddress
        override fun isConnected()=delegate.isConnected
        override fun isBound()=delegate.isBound
        override fun isClosed()=delegate.isClosed
        override fun getSoTimeout()=delegate.soTimeout
        override fun setSoTimeout(timeout:Int){delegate.soTimeout=timeout}
        override fun setTcpNoDelay(on:Boolean){delegate.tcpNoDelay=on}
        override fun getTcpNoDelay()=delegate.tcpNoDelay
        override fun shutdownInput()=delegate.shutdownInput()
        override fun shutdownOutput()=delegate.shutdownOutput()
        override fun close(){if(closedAt==null){closedAt=now();event?.invoke("wire_close",snapshot()+mapOf("interrupted" to Thread.currentThread().isInterrupted))};delegate.close()}
    }

    internal class WireFactory(private val delegate:SSLSocketFactory,
        private val event:(String,String,Map<String,Any?>)->Unit):SSLSocketFactory() {
        override fun getDefaultCipherSuites()=delegate.defaultCipherSuites
        override fun getSupportedCipherSuites()=delegate.supportedCipherSuites
        override fun createSocket(socket:Socket,host:String,port:Int,autoClose:Boolean):Socket =
            delegate.createSocket(WireSocket(socket,SystemClock.elapsedRealtime()){name,fields->event(host,name,fields)},host,port,autoClose)
        override fun createSocket(host:String,port:Int)=delegate.createSocket(host,port)
        override fun createSocket(host:String,port:Int,local:InetAddress,localPort:Int)=delegate.createSocket(host,port,local,localPort)
        override fun createSocket(host:InetAddress,port:Int)=delegate.createSocket(host,port)
        override fun createSocket(host:InetAddress,port:Int,local:InetAddress,localPort:Int)=delegate.createSocket(host,port,local,localPort)
    }
}
