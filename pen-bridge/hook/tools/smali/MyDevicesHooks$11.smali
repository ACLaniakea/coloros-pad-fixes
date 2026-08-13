.class Lcom/aclaniakea/colorosporttuning/MyDevicesHooks$11;
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

    invoke-virtual {p1}, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->getResult()Ljava/lang/Object;

    move-result-object v1

    if-eqz v1, :cond_ret

    check-cast v1, Lcom/oplus/mydevices/domain/entities/cards/DeviceCardDisplayState;

    invoke-virtual {v1}, Lcom/oplus/mydevices/domain/entities/cards/DeviceCardDisplayState;->getIndicatorType()Lcom/oplus/mydevices/domain/entities/cards/IndicatorType;

    move-result-object v4

    invoke-virtual {v1}, Lcom/oplus/mydevices/domain/entities/cards/DeviceCardDisplayState;->getIndicatorLightColor()Lcom/oplus/mydevices/domain/entities/cards/IndicatorLightColor;

    move-result-object v5

    invoke-virtual {v1}, Lcom/oplus/mydevices/domain/entities/cards/DeviceCardDisplayState;->getConnectStateCategory()Lcom/oplus/mydevices/domain/entities/cards/ConnectStateCategory;

    move-result-object v6

    invoke-virtual {v1}, Lcom/oplus/mydevices/domain/entities/cards/DeviceCardDisplayState;->getStatusIconType()Lcom/oplus/mydevices/domain/entities/cards/StatusIconType;

    move-result-object v7

    sget-object v8, Lcom/oplus/mydevices/domain/entities/cards/SubTitleDisplayMode;->BATTERY_ONLY:Lcom/oplus/mydevices/domain/entities/cards/SubTitleDisplayMode;

    new-instance v3, Lcom/oplus/mydevices/domain/entities/cards/DeviceCardDisplayState;

    invoke-direct/range {v3 .. v8}, Lcom/oplus/mydevices/domain/entities/cards/DeviceCardDisplayState;-><init>(Lcom/oplus/mydevices/domain/entities/cards/IndicatorType;Lcom/oplus/mydevices/domain/entities/cards/IndicatorLightColor;Lcom/oplus/mydevices/domain/entities/cards/SubTitleDisplayMode;Lcom/oplus/mydevices/domain/entities/cards/ConnectStateCategory;Lcom/oplus/mydevices/domain/entities/cards/StatusIconType;)V

    invoke-virtual {p1, v3}, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->setResult(Ljava/lang/Object;)V

    const-string v0, "MyDevices pen card display state forced BATTERY_ONLY"

    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :cond_ret
    return-void
.end method
