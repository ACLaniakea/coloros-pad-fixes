.class Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$8;
.super Lde/robv/android/xposed/XC_MethodHook;
.source "MyDevicesHooks.java"


# annotations
.annotation system Ldalvik/annotation/EnclosingMethod;
    value = Lcom/aclaniakea/colorosporttuning/MyDevicesHooks;->install(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x0
    name = null
.end annotation


# direct methods
.method constructor <init>()V
    .registers 1

    invoke-direct {p0}, Lde/robv/android/xposed/XC_MethodHook;-><init>()V

    return-void
.end method


# virtual methods
.method protected beforeHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V
    .registers 8

    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->args:[Ljava/lang/Object;

    array-length v1, v0

    const/4 v2, 0x2

    if-le v1, v2, :cond_ret

    const/4 v1, 0x1

    aget-object v1, v0, v1

    instance-of v2, v1, [I

    if-eqz v2, :cond_ret

    check-cast v1, [I

    array-length v2, v1

    const/4 v3, 0x4

    if-lt v2, v3, :cond_ret

    const/4 v2, 0x0

    aget-object v2, v0, v2

    if-eqz v2, :cond_ret

    check-cast v2, Landroid/bluetooth/BluetoothDevice;

    invoke-virtual {v2}, Landroid/bluetooth/BluetoothDevice;->getAddress()Ljava/lang/String;

    move-result-object v2

    iget-object v3, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->thisObject:Ljava/lang/Object;

    invoke-static {v3}, Lcom/aclaniakea/colorosporttuning/HookUtils;->context(Ljava/lang/Object;)Landroid/content/Context;

    move-result-object v3

    if-eqz v3, :cond_ret

    invoke-static {v3}, Lcom/aclaniakea/colorosporttuning/HookUtils;->state(Landroid/content/Context;)Lcom/aclaniakea/colorosporttuning/PenState;

    move-result-object v3

    if-eqz v3, :cond_ret

    iget-object v4, v3, Lcom/aclaniakea/colorosporttuning/PenState;->address:Ljava/lang/String;

    if-eqz v4, :cond_ret

    const-string v5, ":"

    const-string v6, ""

    invoke-virtual {v4, v5, v6}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;

    move-result-object v4

    invoke-virtual {v4}, Ljava/lang/String;->toLowerCase()Ljava/lang/String;

    move-result-object v4

    invoke-virtual {v2, v5, v6}, Ljava/lang/String;->replace(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)Ljava/lang/String;

    move-result-object v2

    invoke-virtual {v2}, Ljava/lang/String;->toLowerCase()Ljava/lang/String;

    move-result-object v2

    invoke-virtual {v4, v2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v2

    if-eqz v2, :cond_ret

    iget v2, v3, Lcom/aclaniakea/colorosporttuning/PenState;->battery:I

    if-gez v2, :cond_88

    const/4 v4, 0x0

    aput v2, v1, v4

    :cond_88
    iget v2, v3, Lcom/aclaniakea/colorosporttuning/PenState;->charging:I

    const/4 v3, 0x3

    aput v2, v1, v3

    const-string v0, "MyDevices card battery/charging patched"

    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :cond_ret
    return-void
.end method
