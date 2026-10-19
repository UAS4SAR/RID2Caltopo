package org.ncssar.rid2caltopo.mapping

import org.ncssar.rid2caltopo.data.Units
import org.ncssar.rid2caltopo.data.DisplayUnits

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
import java.io.File
import java.util.UUID

/** Internal, protected, offline-only surface inspector. No remote script or network origin. */
class MappingSurfaceViewActivity:Activity(){
    private var browser:WebView?=null
    private var lease:String?=null
    @Volatile private var regionPath="surface.glb"
    override fun onCreate(state:Bundle?){super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val id=intent.getStringExtra("layerId")?:return finish()
        if(runCatching{UUID.fromString(id).toString()==id}.getOrDefault(false).not())return finish()
        val view=WebView(this);browser=view
        view.settings.cacheMode=WebSettings.LOAD_NO_CACHE;view.settings.javaScriptEnabled=true;view.settings.allowFileAccess=false;view.settings.allowContentAccess=false;view.settings.domStorageEnabled=false
        view.settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
        view.webViewClient=object:WebViewClient(){
            override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest?)=request?.url?.host!="appassets.androidplatform.net"
            override fun shouldInterceptRequest(view:WebView?,request:WebResourceRequest?):WebResourceResponse {
                val uri=request?.url
                if(uri?.scheme!="https"||uri.host!="appassets.androidplatform.net")return denied()
                return try{when(uri.path){
                    "/viewer/index.html"->WebResourceResponse("text/html","UTF-8",assets.open("mapping-surface/index.html"))
                    "/viewer/viewer.js"->WebResourceResponse("application/javascript","UTF-8",assets.open("mapping-surface/viewer.js"))
                    "/viewer/scale.js"->WebResourceResponse("application/javascript","UTF-8",assets.open("mapping-surface/scale.js"))
                    "/viewer/collection.js"->WebResourceResponse("application/javascript","UTF-8",assets.open("mapping-surface/collection.js"))
                    "/viewer/boundary.js"->WebResourceResponse("application/javascript","UTF-8",assets.open("mapping-surface/boundary.js"))
                    "/model/$id/boundary.json"->{val file=File(LocalMappingTiles.directory(this@MappingSurfaceViewActivity,id),"layer.json")
                        val layer=MappingLayer.parse(org.json.JSONObject(file.readText()))
                        val boundary=MappingBoundary.resolve(filesDir,layer)?:error("No captured assignment boundary")
                        val data=org.json.JSONObject().put("coordinates",org.json.JSONArray(boundary.coordinates)).put("featherMeters",MappingBoundary.FEATHER_METERS)
                            .put("soft",MappingBoundary.soft(this@MappingSurfaceViewActivity,id))
                        WebResourceResponse("application/json","UTF-8",data.toString().byteInputStream())}
                    "/model/$id/surface.glb"->{val completed=File(LocalMappingTiles.directory(this@MappingSurfaceViewActivity,id),"surface/$regionPath")
                        val working=File(filesDir,"mapping-processing/$id/surface/surface.glb");val file=if(completed.isFile)completed else working
                        require(file.isFile&&file.length() in 20..128L*1024*1024);WebResourceResponse("model/gltf-binary",null,file.inputStream())}
                    else->{val prefix="/model/$id/";val name=uri.path?.removePrefix(prefix).orEmpty()
                        require(uri.path?.startsWith(prefix)==true)
                        require(name=="collection.json" || Regex("regions/[0-9]{1,3}/surface\\.(glb|json)").matches(name))
                        val file=File(LocalMappingTiles.directory(this@MappingSurfaceViewActivity,id),"surface/$name")
                        require(file.isFile && file.length() in 1..32L*1024*1024)
                        WebResourceResponse(if(name.endsWith("json"))"application/json" else "model/gltf-binary",null,200,"OK",mapOf("Content-Length" to file.length().toString()),file.inputStream())}
                }}catch(_:Exception){denied()}
            }
            private fun denied()=WebResourceResponse("text/plain","UTF-8",404,"Not found",emptyMap(),"Local surface unavailable".byteInputStream())
        }
        val layout=android.widget.LinearLayout(this).apply{orientation=android.widget.LinearLayout.VERTICAL;setBackgroundColor(android.graphics.Color.rgb(23,39,48))}
        val collection=File(LocalMappingTiles.directory(this,id),"surface/collection.json")
        if(collection.isFile) {
            val regions=org.json.JSONObject(collection.readText()).getJSONArray("regions")
            require(regions.length() in 1..200)
            val paths=(0 until regions.length()).map { i->regions.getJSONObject(i).getString("path").also { require(it=="regions/$i/surface.glb") } }
            regionPath=""
            layout.addView(android.widget.TextView(this).apply {
                text="This map contains ${paths.size} 3D regions. View them together or inspect one region."
                setTextColor(android.graphics.Color.WHITE);textSize=16f;setPadding(16,12,16,8)
            })
            val navigation=android.widget.LinearLayout(this).apply{orientation=android.widget.LinearLayout.HORIZONTAL}
            val previous=android.widget.Button(this).apply{text="Previous region"}
            val next=android.widget.Button(this).apply{text="Next region"}
            val selector=android.widget.Spinner(this)
            selector.adapter=object:android.widget.ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,listOf("All ${paths.size} regions together") + (paths.indices).map { "3D region ${it+1} of ${paths.size} · 0.4 m mesh" }) {
                override fun getView(position:Int,convertView:android.view.View?,parent:android.view.ViewGroup):android.view.View =
                    super.getView(position,convertView,parent).also{(it as? android.widget.TextView)?.setTextColor(android.graphics.Color.WHITE)}
            }
            selector.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,v:android.view.View?,position:Int,itemId:Long) {
                    previous.isEnabled=position>0;next.isEnabled=position<paths.size
                    regionPath=if(position==0)"" else paths[position-1]
                    view.loadUrl("https://appassets.androidplatform.net/viewer/index.html?units=${Units.current.name.lowercase()}&surface=${android.net.Uri.encode(regionPath)}#$id")
                }
            }
            previous.setOnClickListener{selector.setSelection((selector.selectedItemPosition-1).coerceAtLeast(0))}
            next.setOnClickListener{selector.setSelection((selector.selectedItemPosition+1).coerceAtMost(paths.size))}
            navigation.addView(previous);navigation.addView(selector,android.widget.LinearLayout.LayoutParams(0,android.view.ViewGroup.LayoutParams.WRAP_CONTENT,1f));navigation.addView(next)
            layout.addView(navigation)
        }
        layout.addView(view,android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        layout.addView(android.widget.Button(this).apply{text=if(File(LocalMappingTiles.directory(this@MappingSurfaceViewActivity,id),"layer.json").isFile)"View 2D map" else "Return to processing";setOnClickListener{finish()}})
        setContentView(layout);view.loadUrl("https://appassets.androidplatform.net/viewer/index.html?units=${Units.current.name.lowercase()}#$id")
    }
    override fun onDestroy(){browser?.destroy();browser=null;super.onDestroy()}
    override fun onResume(){super.onResume();if(lease==null)lease=MappingWorkRuntime.begin{runOnUiThread{finish()}}}
    override fun onPause(){lease?.let(MappingWorkRuntime::finish);lease=null;super.onPause()}
}
