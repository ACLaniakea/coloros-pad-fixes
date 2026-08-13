.class Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$9;
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
.method protected afterHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V
    .registers 10

    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->thisObject:Ljava/lang/Object;

    if-eqz v0, :cond_ret

    check-cast v0, Lcom/oplus/mydevices/domain/entities/cards/QuickCardDeviceData;

    const-string v1, "MyDevices getBatteryMain called"

    invoke-static {v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    invoke-virtual {v0}, Lcom/oplus/mydevices/domain/entities/cards/QuickCardDeviceData;->getName()Ljava/lang/String;

    move-result-object v1

    if-eqz v1, :cond_ret

    invoke-virtual {v1}, Ljava/lang/String;->toLowerCase()Ljava/lang/String;

    move-result-object v1

    const-string v2, "lenovo"

    invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z

    move-result v2

    if-eqz v2, :cond_ret

    const-string v2, "pen"

    invoke-virtual {v1, v2}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z

    move-result v1

    if-eqz v1, :cond_ret

    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->context(Ljava/lang/Object;)Landroid/content/Context;

    move-result-object v0

    if-eqz v0, :cond_ret

    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->state(Landroid/content/Context;)Lcom/aclaniakea/colorosporttuning/PenState;

    move-result-object v0

    if-eqz v0, :cond_ret

    iget v1, v0, Lcom/aclaniakea/colorosporttuning/PenState;->battery:I

    if-gez v1, :cond_53

    const/4 v1, 0x0

    :cond_53
    iget v2, v0, Lcom/aclaniakea/colorosporttuning/PenState;->charging:I

    if-nez v2, :cond_59

    const/4 v2, 0x0

    goto :goto_5a

    :cond_59
    const/4 v2, 0x1

    :goto_5a
    new-instance v3, Lcom/oplus/mydevices/domain/entities/device/BatteryInfo;

    sget-object v4, Lcom/oplus/mydevices/domain/entities/device/BatteryType;->SINGLE:Lcom/oplus/mydevices/domain/entities/device/BatteryType;

    invoke-direct {v3, v4, v1, v2}, Lcom/oplus/mydevices/domain/entities/device/BatteryInfo;-><init>(Lcom/oplus/mydevices/domain/entities/device/BatteryType;IZ)V

    invoke-virtual {p1, v3}, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->setResult(Ljava/lang/Object;)V

    new-instance v0, Ljava/lang/StringBuilder;

    invoke-direct {v0}, Ljava/lang/StringBuilder;-><init>()V

    const-string v1, "MyDevices card batteryMain override level="

    invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v0, v3}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :cond_ret
    return-void
.end method
