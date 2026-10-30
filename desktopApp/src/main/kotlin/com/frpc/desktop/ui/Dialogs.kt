package com.frpc.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.frpc.shared.net.ListeningPort
import com.frpc.shared.net.LocalPorts
import com.frpc.shared.model.ProxyConfig
import com.frpc.shared.model.ProxyPluginConfig
import com.frpc.shared.model.ProxyTransportConfig
import com.frpc.shared.model.ProxyType
import com.frpc.shared.model.VisitorConfig
import com.frpc.shared.ports.PortRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import kotlin.random.Random

@Composable
fun ProxyEditDialog(
    initial: ProxyConfig?,
    localUser: String? = null,
    takenNames: Set<String> = emptySet(),
    onDismiss: () -> Unit,
    onSave: (List<ProxyConfig>) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: ProxyType.TCP.id) }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var localIP by remember { mutableStateOf(initial?.localIP ?: "127.0.0.1") }
    var localPort by remember { mutableStateOf(initial?.localPort?.toString() ?: "") }
    var remotePort by remember { mutableStateOf(initial?.remotePort?.toString() ?: "") }
    var subdomain by remember { mutableStateOf(initial?.subdomain ?: "") }
    var customDomains by remember { mutableStateOf(initial?.customDomains?.joinToString(",") ?: "") }
    var httpUser by remember { mutableStateOf(initial?.httpUser ?: "") }
    var hostHeaderRewrite by remember { mutableStateOf(initial?.hostHeaderRewrite ?: "") }
    var httpPassword by remember { mutableStateOf(initial?.httpPassword ?: "") }
    var secretKey by remember { mutableStateOf(initial?.secretKey ?: "") }
    var allowUsers by remember { mutableStateOf(initial?.allowUsers?.joinToString(",") ?: "") }
    var encryption by remember { mutableStateOf(initial?.transport?.useEncryption ?: false) }
    var compression by remember { mutableStateOf(initial?.transport?.useCompression ?: false) }
    var bandwidth by remember { mutableStateOf(initial?.transport?.bandwidthLimit ?: "") }
    var bandwidthMode by remember { mutableStateOf(initial?.transport?.bandwidthLimitMode ?: "client") }
    var locations by remember { mutableStateOf(initial?.locations?.joinToString(",") ?: "") }
    var routeByHTTPUser by remember { mutableStateOf(initial?.routeByHTTPUser ?: "") }
    var proxyProtocolVersion by remember { mutableStateOf(initial?.transport?.proxyProtocolVersion ?: "") }

    val originalPlugin = initial?.plugin
    val originalIsHttpsPlugin = originalPlugin?.isHttpsPlugin == true
    var pluginType by remember {
        mutableStateOf(originalPlugin?.takeIf { it.isHttpsPlugin }?.type.orEmpty())
    }
    var pluginLocalAddr by remember { mutableStateOf(originalPlugin?.localAddr.orEmpty()) }
    var pluginCrt by remember { mutableStateOf(originalPlugin?.crtPath.orEmpty()) }
    var pluginKey by remember { mutableStateOf(originalPlugin?.keyPath.orEmpty()) }

    var formError by remember { mutableStateOf<String?>(null) }
    var showPortPicker by remember { mutableStateOf(false) }

    val proxyType = ProxyType.fromId(type) ?: ProxyType.TCP
    val batchCapable = proxyType == ProxyType.TCP || proxyType == ProxyType.UDP
    val batchMode = batchCapable && (PortRange.isBatch(localPort) || PortRange.isBatch(remotePort))

    fun buildOne(overrideLocal: Int? = null, overrideRemote: Int? = null): ProxyConfig = ProxyConfig(
        name = name.trim(),
        type = type,
        enabled = if (enabled) null else false,
        localIP = localIP.trim().ifBlank { "127.0.0.1" },
        localPort = overrideLocal ?: localPort.toIntOrNull(),
        remotePort = overrideRemote ?: remotePort.toIntOrNull(),
        subdomain = subdomain.trim().ifBlank { null },
        customDomains = splitList(customDomains).ifEmpty { null },
        locations = splitList(locations).ifEmpty { null },
        httpUser = httpUser.trim().ifBlank { null },
        httpPassword = httpPassword.trim().ifBlank { null },
        hostHeaderRewrite = hostHeaderRewrite.trim().ifBlank { null },
        routeByHTTPUser = routeByHTTPUser.trim().ifBlank { null },
        secretKey = secretKey.trim().ifBlank { null },
        allowUsers = splitList(allowUsers).ifEmpty { null },
        multiplexer = if (proxyType == ProxyType.TCPMUX) "httpconnect" else null,
        transport = ProxyTransportConfig(
            bandwidthLimit = bandwidth.trim().ifBlank { null },
            bandwidthLimitMode = bandwidthMode.takeIf { bandwidth.isNotBlank() },
            useEncryption = encryption.takeIf { it },
            useCompression = compression.takeIf { it },
            proxyProtocolVersion = proxyProtocolVersion.ifBlank { null },
        ).takeIf {
            it.bandwidthLimit != null || it.useEncryption == true ||
                it.useCompression == true || it.proxyProtocolVersion != null
        },
        plugin = when {
            proxyType == ProxyType.HTTPS && pluginType.isNotBlank() -> ProxyPluginConfig(
                type = pluginType,
                localAddr = pluginLocalAddr.trim().ifBlank { null },
                crtPath = pluginCrt.trim().ifBlank { null },
                keyPath = pluginKey.trim().ifBlank { null },
            )
            originalPlugin != null && !originalIsHttpsPlugin -> originalPlugin
            else -> null
        },
    ).sanitizedForType()

    fun buildAll(): List<ProxyConfig>? {
        formError = null
        if (name.isBlank()) {
            formError = "请填写代理名称"
            return null
        }
        if (!batchMode) return listOf(buildOne())
        val locals = when (val r = PortRange.parse(localPort)) {
            is PortRange.ParseResult.Ok -> r
            is PortRange.ParseResult.Failed -> {
                formError = portErrorText("内网端口", r)
                return null
            }
        }
        val remoteExpr = remotePort.trim()
        val remotes = if (remoteExpr.isBlank()) {
            emptyList()
        } else {
            when (val r = PortRange.parse(remoteExpr)) {
                is PortRange.ParseResult.Ok -> r.ports
                is PortRange.ParseResult.Failed -> {
                    formError = portErrorText("外网端口", r)
                    return null
                }
            }
        }
        if (remotes.isNotEmpty() && remotes.size != locals.ports.size) {
            formError = "内网端口 ${locals.ports.size} 个、外网端口 ${remotes.size} 个，数量必须一致"
            return null
        }
        val names = locals.ports.map { PortRange.proxyName(name, it) }
        val conflict = names.firstOrNull { it in takenNames }
        if (conflict != null) {
            formError = "批量生成的代理名「$conflict」已存在，请换一个名称"
            return null
        }
        return locals.ports.mapIndexed { index, p ->
            buildOne(overrideLocal = p, overrideRemote = remotes.getOrNull(index))
                .copy(name = names[index])
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 760.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    if (initial == null) "新建代理规则" else "编辑代理规则",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "在 frps 上的名字：" +
                        com.frpc.shared.model.FrpcNaming.targetServerProxyName(localUser, null, name.ifBlank { "…" }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    DialogSection("基本")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LabeledTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = "代理名称",
                                supportingText = "frps 上唯一，建议用英文/数字",
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = {
                                repeat(20) {
                                    val candidate = "${type.lowercase()}-${Random.nextInt(1000, 9999)}"
                                    if (candidate !in takenNames && candidate != name) {
                                        name = candidate
                                        return@OutlinedButton
                                    }
                                }
                            }) { Text("生成") }
                        }
                        DropdownField(
                            label = "代理类型",
                            selected = proxyType,
                            options = ProxyType.entries,
                            optionLabel = { proxyTypeLabel(it) },
                            onSelect = {
                                type = it.id
                                if (!it.needsRemotePort) remotePort = ""
                                if (!it.domainBased) {
                                    subdomain = ""
                                    customDomains = ""
                                }
                                if (!it.supportsHttpAdvanced) {
                                    locations = ""
                                    hostHeaderRewrite = ""
                                }
                                if (!it.supportsHttpAuth) {
                                    httpUser = ""
                                    httpPassword = ""
                                    routeByHTTPUser = ""
                                }
                                if (!it.needsSecretKey) {
                                    secretKey = ""
                                    allowUsers = ""
                                }
                                if (it != ProxyType.HTTPS) {
                                    proxyProtocolVersion = ""
                                    pluginType = ""
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SwitchRow(
                        label = "启用该代理",
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        supportingText = "关闭时写入 enabled = false，frpc 不会注册这条代理",
                    )

                    DialogSection("本地服务")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledTextField(
                            value = localIP,
                            onValueChange = { localIP = it },
                            label = "本地地址",
                            placeholder = "127.0.0.1",
                            supportingText = "frpc 所在机器上要暴露的服务地址",
                            modifier = Modifier.weight(2f),
                        )
                        Row(
                            modifier = Modifier.weight(1.6f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LabeledTextField(
                                value = localPort,
                                onValueChange = { localPort = it },
                                label = "本地端口",
                                placeholder = if (batchCapable) "8080 或 8080-8085" else "8080",
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { showPortPicker = true }) { Text("本机端口") }
                        }
                    }

                    if (proxyType.needsRemotePort) {
                        DialogSection("对外入口")
                        LabeledTextField(
                            value = remotePort,
                            onValueChange = { remotePort = it },
                            label = "远程端口",
                            placeholder = if (batchCapable) "留空 = 随机；支持 18080-18085" else "留空 = 随机",
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                            supportingText = if (PortRange.isBatch(remotePort)) {
                                "批量时必须与本地端口数量一致"
                            } else {
                                "frps 上监听的端口；留空或填 0 由 frps 随机分配，启动后主页显示实际端口"
                            },
                        )
                    }
                    if (batchMode) {
                        val parsed = PortRange.parse(localPort)
                        if (parsed is PortRange.ParseResult.Ok) {
                            val names = parsed.ports.map { PortRange.proxyName(name.ifBlank { "代理" }, it) }
                            Text(
                                text = "将创建 ${parsed.ports.size} 条：${names.first()} … ${names.last()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                text = "端口表达式有误，请检查",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (proxyType.domainBased) {
                        if (!proxyType.needsRemotePort) DialogSection("对外入口")
                        LabeledTextField(
                            value = customDomains,
                            onValueChange = { customDomains = it },
                            label = "自定义域名",
                            placeholder = "web.example.com, www.example.com",
                            supportingText = "多个用逗号分隔；与子域名至少填一个，也可以都填",
                        )
                        LabeledTextField(
                            value = subdomain,
                            onValueChange = { subdomain = it },
                            label = "子域名",
                            placeholder = "web01",
                            supportingText = if (proxyType == ProxyType.TCPMUX) {
                                "tcpmux 用它做 HTTP CONNECT 的入口域名；不能带 . 或 *"
                            } else {
                                "frps 的 subDomainHost 已配置时可用；只填一级名字，不能带 . 或 *"
                            },
                        )
                    }
                    if (proxyType.supportsMultiplexer) {
                        DropdownField(
                            label = "多路复用器",
                            selected = "httpconnect",
                            options = listOf("httpconnect"),
                            optionLabel = { it },
                            onSelect = { },
                        )
                        Text(
                            "tcpmux 目前只支持 httpconnect：客户端用 HTTP CONNECT 把连接交给 frps",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (proxyType.supportsHttpAuth || proxyType.supportsHttpAdvanced || proxyType.needsSecretKey) {
                        DialogSection("访问控制")
                    }
                    if (proxyType.supportsHttpAuth) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LabeledTextField(
                                value = httpUser,
                                onValueChange = { httpUser = it },
                                label = "HTTP 账号（可选）",
                                supportingText = "访问时弹 Basic Auth",
                                modifier = Modifier.weight(1f),
                            )
                            LabeledTextField(
                                value = httpPassword,
                                onValueChange = { httpPassword = it },
                                label = "HTTP 密码（可选）",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        LabeledTextField(
                            value = routeByHTTPUser,
                            onValueChange = { routeByHTTPUser = it },
                            label = "按 HTTP 账号路由（可选）",
                            placeholder = "user1",
                            supportingText = "同一域名下按 Basic Auth 账号分流到不同代理",
                        )
                    }
                    if (proxyType.supportsHttpAdvanced) {
                        LabeledTextField(
                            value = locations,
                            onValueChange = { locations = it },
                            label = "路由路径（可选）",
                            placeholder = "/, /pic",
                            supportingText = "仅 http：按 URL 前缀分流，多个用逗号分隔",
                        )
                        LabeledTextField(
                            value = hostHeaderRewrite,
                            onValueChange = { hostHeaderRewrite = it },
                            label = "Host 头重写（可选）",
                            placeholder = "内网服务认的域名，如 nas.local",
                            supportingText = "后端校验 Host 时填这里：frps 会把 Host 改成它再转发",
                        )
                    }
                    if (proxyType.needsSecretKey) {
                        LabeledTextField(
                            value = secretKey,
                            onValueChange = { secretKey = it },
                            label = "私密密钥",
                            supportingText = "访问者必须填同一个密钥，否则握手失败",
                        )
                        LabeledTextField(
                            value = allowUsers,
                            onValueChange = { allowUsers = it },
                            label = "允许访问的账号（可选）",
                            placeholder = "user1, user2 或 *",
                            supportingText = "填 user 前缀；留空=仅本 user 可访问，* = 允许所有 user",
                        )
                    }

                    if (proxyType.supportsHttpsPlugin) {
                        DialogSection("插件（TLS 由 frpc 终结）")
                        DropdownField(
                            label = "插件类型",
                            selected = pluginType,
                            options = listOf("", ProxyPluginConfig.TYPE_HTTPS2HTTP, ProxyPluginConfig.TYPE_TLS2RAW),
                            optionLabel = {
                                when (it) {
                                    ProxyPluginConfig.TYPE_HTTPS2HTTP -> "https2http · 本地服务用 HTTP"
                                    ProxyPluginConfig.TYPE_TLS2RAW -> "tls2raw · 本地服务收原始 TCP"
                                    else -> "不使用插件（SNI 透传，本地自己配证书）"
                                }
                            },
                            onSelect = { v ->
                                pluginType = v
                                if (v.isNotBlank() && pluginLocalAddr.isBlank()) {
                                    pluginLocalAddr = "${localIP.trim().ifBlank { "127.0.0.1" }}:${localPort.trim()}"
                                }
                            },
                        )
                        if (pluginType.isNotBlank()) {
                            LabeledTextField(
                                value = pluginLocalAddr,
                                onValueChange = { pluginLocalAddr = it },
                                label = "插件本地地址 localAddr",
                                placeholder = "127.0.0.1:8080",
                                supportingText = "启用插件后 localIP/localPort 不再生效，以这里为准",
                            )
                            FilePathField(
                                value = pluginCrt.ifBlank { null },
                                onValueChange = { pluginCrt = it.orEmpty() },
                                label = "证书文件 crtPath",
                                placeholder = "/etc/frp/web.crt",
                                onBrowse = {
                                    chooseFile("选择证书文件", FileDialog.LOAD)?.let { pluginCrt = it.absolutePath }
                                },
                            )
                            FilePathField(
                                value = pluginKey.ifBlank { null },
                                onValueChange = { pluginKey = it.orEmpty() },
                                label = "私钥文件 keyPath",
                                placeholder = "/etc/frp/web.key",
                                onBrowse = {
                                    chooseFile("选择私钥文件", FileDialog.LOAD)?.let { pluginKey = it.absolutePath }
                                },
                            )
                        }
                    } else if (originalPlugin != null && !originalIsHttpsPlugin) {
                        DialogSection("插件")
                        Text(
                            "该代理带插件「${originalPlugin.type}」（导入保留）。App 不编辑这类插件，" +
                                "保存时会原样写回；需要修改请导出 frpc.toml。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    DialogSection("传输")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow(
                            label = "加密",
                            checked = encryption,
                            onCheckedChange = { encryption = it },
                            supportingText = "用 token 派生密钥加密该代理流量",
                            modifier = Modifier.weight(1f),
                        )
                        SwitchRow(
                            label = "压缩",
                            checked = compression,
                            onCheckedChange = { compression = it },
                            supportingText = "省带宽、增加 CPU 开销",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledTextField(
                            value = bandwidth,
                            onValueChange = { bandwidth = it },
                            label = "带宽限制（可选）",
                            placeholder = "1MB",
                            supportingText = "必须带单位 KB 或 MB，例如 512KB、1.5MB",
                            modifier = Modifier.weight(1f),
                        )
                        if (bandwidth.isNotBlank()) {
                            DropdownField(
                                label = "限速位置",
                                selected = bandwidthMode,
                                options = listOf("client", "server"),
                                optionLabel = { if (it == "client") "client（本机）" else "server（frps 侧）" },
                                onSelect = { bandwidthMode = it },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (proxyType == ProxyType.HTTPS) {
                        DropdownField(
                            label = "PROXY Protocol",
                            selected = proxyProtocolVersion,
                            options = listOf("", "v1", "v2"),
                            optionLabel = { if (it.isEmpty()) "不启用" else it },
                            onSelect = { proxyProtocolVersion = it },
                        )
                        Text(
                            "开启后把真实来源 IP/端口写进连接头，本地服务需支持 PROXY Protocol 才能解析",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    formError?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = { buildAll()?.let(onSave) },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text(if (batchMode) "批量创建" else "确定") }
                }
            }
        }
    }

    if (showPortPicker) {
        LocalPortPickerDialog(
            onDismiss = { showPortPicker = false },
            onSelect = { port ->
                localPort = port.toString()
                showPortPicker = false
            },
        )
    }
}

private fun portErrorText(label: String, failed: PortRange.ParseResult.Failed): String = when (failed.reason) {
    PortRange.Reason.EMPTY -> "$label 不能为空"
    PortRange.Reason.INVALID -> "$label 写法不正确" + (failed.detail?.let { "：$it" } ?: "")
    PortRange.Reason.REVERSED -> "$label 区间写反了（起始不能大于结束）"
    PortRange.Reason.OUT_OF_RANGE -> "$label 超出 1-65535"
    PortRange.Reason.TOO_MANY -> "$label 一次最多展开 ${PortRange.MAX_EXPAND} 条"
}

@Composable
private fun LocalPortPickerDialog(onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    var ports by remember { mutableStateOf<List<ListeningPort>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        ports = withContext(Dispatchers.IO) { LocalPorts.list() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 520.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("本机端口", style = MaterialTheme.typography.titleMedium)
                Text(
                    "系统当前正在监听的端口；点一行填入本地端口",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索端口 / 地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                val list = ports
                when {
                    list == null -> Text(
                        "扫描中…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                    list.isEmpty() -> Text(
                        "没有扫描到监听端口（可能权限不足），请手动输入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                    else -> {
                        val filtered = list.filter {
                            query.isBlank() || it.port.toString().contains(query) || it.address.contains(query)
                        }
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp).padding(top = 8.dp)) {
                            items(filtered) { p ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelect(p.port) }
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("${p.port}", style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${p.protocol.uppercase()} · ${p.address}" + (p.pid?.let { " · PID $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
fun VisitorEditDialog(
    initial: VisitorConfig?,
    localUser: String? = null,
    onDismiss: () -> Unit,
    onSave: (VisitorConfig) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "stcp") }
    var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
    var serverName by remember { mutableStateOf(initial?.serverName ?: "") }
    var secretKey by remember { mutableStateOf(initial?.secretKey ?: "") }
    var bindAddr by remember { mutableStateOf(initial?.bindAddr ?: "127.0.0.1") }
    var bindPort by remember { mutableStateOf((initial?.bindPort ?: 6000).toString()) }
    var serverUser by remember { mutableStateOf(initial?.serverUser ?: "") }
    var keepTunnelOpen by remember { mutableStateOf(initial?.keepTunnelOpen ?: false) }
    var fallbackTo by remember { mutableStateOf(initial?.fallbackTo ?: "") }
    var fallbackTimeoutMs by remember { mutableStateOf(initial?.fallbackTimeoutMs?.toString() ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, modifier = Modifier.widthIn(max = 620.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    if (initial == null) "新建访问者" else "编辑访问者",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "访问者 = 主动连接别人的私密代理，在本机开一个端口给本地程序用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = "访问者名称",
                            supportingText = "只在本机生效，frps 不感知",
                            modifier = Modifier.weight(1f),
                        )
                        DropdownField(
                            label = "类型",
                            selected = type,
                            options = listOf("stcp", "sudp", "xtcp"),
                            optionLabel = { visitorTypeLabel(it) },
                            onSelect = { type = it },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    SwitchRow(
                        label = "启用该访问者",
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        supportingText = "关闭时写入 enabled = false，frpc 不会去连对方的私密代理",
                    )

                    DialogSection("要访问谁")
                    LabeledTextField(
                        value = serverName,
                        onValueChange = { serverName = it },
                        label = "对方代理名",
                        placeholder = "secret_tcp",
                        supportingText = "只填代理名本身，不要带对方的前缀（对应 serverName）",
                    )
                    LabeledTextField(
                        value = serverUser,
                        onValueChange = { serverUser = it },
                        label = "对方设备前缀（可选）",
                        placeholder = "对方配置里的 user",
                        supportingText = if (localUser.isNullOrBlank()) {
                            "本机没设前缀：留空表示不加前缀；访问别的设备时填对方的 user"
                        } else {
                            "留空 = 用本机前缀「$localUser」；访问别的设备时填对方的 user"
                        },
                    )
                    Text(
                        "frps 上实际查找：${
                            com.frpc.shared.model.FrpcNaming.targetServerProxyName(
                                localUser, serverUser.trim().ifBlank { null }, serverName.ifBlank { "…" },
                            )
                        }",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    com.frpc.shared.model.FrpcNaming.splitPrefixedName(serverName)?.let { (prefix, rest) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "要访问 $prefix 的 $rest？代理名不该带前缀，点右侧自动拆开",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                serverUser = prefix
                                serverName = rest
                            }) { Text("按此改") }
                        }
                    }
                    LabeledTextField(
                        value = secretKey,
                        onValueChange = { secretKey = it },
                        label = "私密密钥",
                        supportingText = "必须与对方代理的 secretKey 完全一致",
                    )

                    DialogSection("本机监听")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LabeledTextField(
                            value = bindAddr,
                            onValueChange = { bindAddr = it },
                            label = "监听地址",
                            supportingText = "默认只监听本机；要让局域网访问填 0.0.0.0",
                            modifier = Modifier.weight(2f),
                        )
                        NumberField(
                            value = bindPort,
                            onValueChange = { bindPort = it },
                            label = "监听端口",
                            allowNegative = true,
                            modifier = Modifier.weight(1f),
                            supportingText = "填 -1 = 不监听（sudp 不支持）",
                        )
                    }

                    if (type == "xtcp") {
                        DialogSection("xtcp 打洞")
                        SwitchRow(
                            label = "保持隧道（keepTunnelOpen）",
                            checked = keepTunnelOpen,
                            onCheckedChange = { keepTunnelOpen = it },
                            supportingText = "访问者会持续尝试打通并保持 P2P 隧道，代价是常驻流量",
                        )
                        Text(
                            "xtcp 靠 UDP 打洞，不一定每次成功；建议再建一条指向同一代理的 stcp 访问者，" +
                                "并在下面填它的名字，打洞失败自动改走 frps 中转。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LabeledTextField(
                                value = fallbackTo,
                                onValueChange = { fallbackTo = it },
                                label = "打洞失败转交给（可选）",
                                placeholder = "另建一个 stcp 访问者，填它的名字",
                                supportingText = "填的是访问者名称，不是代理名",
                                modifier = Modifier.weight(2f),
                            )
                            NumberField(
                                value = fallbackTimeoutMs,
                                onValueChange = { fallbackTimeoutMs = it },
                                label = "等待时长",
                                placeholder = "1000",
                                supportingText = "毫秒；留空 = 1000",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(
                        onClick = {
                            onSave(
                                VisitorConfig(
                                    name = name.trim(),
                                    type = type,
                                    serverName = serverName.trim(),
                                    secretKey = secretKey.trim().ifBlank { null },
                                    bindAddr = bindAddr.trim().ifBlank { "127.0.0.1" },
                                    bindPort = bindPort.trim().toIntOrNull() ?: 0,
                                    serverUser = serverUser.trim().ifBlank { null },
                                    enabled = if (enabled) null else false,
                                    fallbackTo = fallbackTo.trim().ifBlank { null },
                                    fallbackTimeoutMs = fallbackTimeoutMs.trim().toIntOrNull(),
                                    keepTunnelOpen = keepTunnelOpen,
                                ).sanitizedForType(),
                            )
                        },
                        enabled = name.isNotBlank() && serverName.isNotBlank(),
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text("确定") }
                }
            }
        }
    }
}

@Composable
private fun DialogSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

private fun splitList(text: String): List<String> =
    text.split(',', '，', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }

private fun proxyTypeLabel(type: ProxyType): String = when (type) {
    ProxyType.TCP -> "TCP · 端口转发"
    ProxyType.UDP -> "UDP · 端口转发"
    ProxyType.HTTP -> "HTTP · 域名转发"
    ProxyType.HTTPS -> "HTTPS · 域名转发"
    ProxyType.TCPMUX -> "TCPMUX · 端口复用"
    ProxyType.STCP -> "STCP · 私密 TCP"
    ProxyType.SUDP -> "SUDP · 私密 UDP"
    ProxyType.XTCP -> "XTCP · P2P 打洞"
}

private fun visitorTypeLabel(type: String): String = when (type) {
    "stcp" -> "STCP · 私密 TCP"
    "sudp" -> "SUDP · 私密 UDP"
    "xtcp" -> "XTCP · P2P 打洞"
    else -> type
}
