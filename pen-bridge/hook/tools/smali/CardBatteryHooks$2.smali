.class Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$2;
.super Lde/robv/android/xposed/XC_MethodHook;
.source "CardBatteryHooks.java"


# annotations
.annotation system Ldalvik/annotation/EnclosingMethod;
    value = Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->install(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V
.end annotation

.annotation system Ldalvik/annotation/InnerClass;
    accessFlags = 0x0
    name = null
.end annotation


# direct methods
.method constructor <init>()V
    .registers 1

    .line 62
    invoke-direct {p0}, Lde/robv/android/xposed/XC_MethodHook;-><init>()V

    return-void
.end method


# virtual methods
.method protected afterHookedMethod(Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;)V
    .registers 16
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Throwable;
        }
    .end annotation

    .line 66
    :try_start_0
    iget-object v0, p1, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->thisObject:Ljava/lang/Object;

    if-nez v0, :cond_5

    return-void

    .line 70
    :cond_5
    const-string v1, "getName"

    const/4 v2, 0x0

    new-array v3, v2, [Ljava/lang/Object;

    invoke-static {v0, v1, v3}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v1

    invoke-static {v1}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;

    move-result-object v1

    .line 71
    invoke-virtual {v1}, Ljava/lang/String;->toLowerCase()Ljava/lang/String;

    move-result-object v1

    .line 72
    const-string v3, "lenovo"

    invoke-virtual {v1, v3}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z

    move-result v3

    if-eqz v3, :cond_b7

    const-string v3, "pen"

    invoke-virtual {v1, v3}, Ljava/lang/String;->contains(Ljava/lang/CharSequence;)Z

    move-result v1

    if-nez v1, :cond_28

    goto/16 :goto_b7

    .line 75
    :cond_28
    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->context(Ljava/lang/Object;)Landroid/content/Context;

    move-result-object v0

    if-nez v0, :cond_2f

    return-void

    .line 79
    :cond_2f
    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->state(Landroid/content/Context;)Lcom/aclaniakea/colorosporttuning/PenState;

    move-result-object v0

    if-nez v0, :cond_36

    return-void

    .line 83
    :cond_36
    invoke-virtual {p1}, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->getResult()Ljava/lang/Object;

    move-result-object v1

    if-nez v1, :cond_3d

    return-void

    .line 87
    :cond_3d
    const-string v3, "getIndicatorType"

    new-array v4, v2, [Ljava/lang/Object;

    invoke-static {v1, v3, v4}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v3

    .line 88
    const-string v4, "getIndicatorLightColor"

    new-array v5, v2, [Ljava/lang/Object;

    invoke-static {v1, v4, v5}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v4

    .line 89
    const-string v5, "getConnectStateCategory"

    new-array v6, v2, [Ljava/lang/Object;

    invoke-static {v1, v5, v6}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v5

    .line 90
    const-string v6, "getStatusIconType"

    new-array v7, v2, [Ljava/lang/Object;

    invoke-static {v1, v6, v7}, Lcom/aclaniakea/colorosporttuning/HookUtils;->call(Ljava/lang/Object;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v6

    .line 91
    const-string v7, "com.oplus.mydevices.domain.entities.cards.SubTitleDisplayMode"

    # getter for: Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->appLoader:Ljava/lang/ClassLoader;
    invoke-static {}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->access$100()Ljava/lang/ClassLoader;

    move-result-object v8

    invoke-static {v7, v2, v8}, Ljava/lang/Class;->forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;

    move-result-object v7

    .line 92
    const-class v8, Ljava/lang/Enum;

    invoke-virtual {v7, v8}, Ljava/lang/Class;->asSubclass(Ljava/lang/Class;)Ljava/lang/Class;

    move-result-object v8

    iget-boolean v0, v0, Lcom/aclaniakea/colorosporttuning/PenState;->connected:Z

    if-eqz v0, :cond_74

    const-string v0, "BATTERY_ONLY"

    goto :goto_76

    :cond_74
    const-string v0, "TITLE_ONLY"

    :goto_76
    invoke-static {v8, v0}, Ljava/lang/Enum;->valueOf(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;

    move-result-object v0

    .line 93
    invoke-virtual {v1}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    move-result-object v1

    .line 94
    invoke-virtual {v3}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    move-result-object v8

    invoke-virtual {v4}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    move-result-object v9

    invoke-virtual {v5}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    move-result-object v10

    invoke-virtual {v6}, Ljava/lang/Object;->getClass()Ljava/lang/Class;

    move-result-object v11

    const/4 v12, 0x5

    new-array v13, v12, [Ljava/lang/Class;

    aput-object v8, v13, v2

    const/4 v8, 0x1

    aput-object v9, v13, v8

    const/4 v9, 0x2

    aput-object v7, v13, v9

    const/4 v7, 0x3

    aput-object v10, v13, v7

    const/4 v10, 0x4

    aput-object v11, v13, v10

    invoke-virtual {v1, v13}, Ljava/lang/Class;->getConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;

    move-result-object v1

    .line 95
    new-array v11, v12, [Ljava/lang/Object;

    aput-object v3, v11, v2

    aput-object v4, v11, v8

    aput-object v0, v11, v9

    aput-object v5, v11, v7

    aput-object v6, v11, v10

    invoke-virtual {v1, v11}, Ljava/lang/reflect/Constructor;->newInstance([Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object v0

    invoke-virtual {p1, v0}, Lde/robv/android/xposed/XC_MethodHook$MethodHookParam;->setResult(Ljava/lang/Object;)V
    :try_end_b6
    .catchall {:try_start_0 .. :try_end_b6} :catchall_b8

    goto :goto_ca

    :cond_b7
    :goto_b7
    return-void

    :catchall_b8
    move-exception p1

    .line 97
    new-instance v0, Ljava/lang/StringBuilder;

    const-string v1, "CardBatteryHooks state: "

    invoke-direct {v0, v1}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {v0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    invoke-virtual {v0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p1

    invoke-static {p1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :goto_ca
    return-void
.end method
