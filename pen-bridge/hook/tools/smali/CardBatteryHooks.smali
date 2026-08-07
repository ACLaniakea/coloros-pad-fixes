.class final Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;
.super Ljava/lang/Object;
.source "CardBatteryHooks.java"


# static fields
.field private static final ID_BATTERY_TEXT:I = 0x7f09009e

.field private static final ID_BATTERY_VIEW:I = 0x7f09009f

.field private static final ID_SINGLE_BATTERY:I = 0x7f0903b6

.field private static final ID_STATUS_CONTAINER:I = 0x7f0903e9

.field private static final ID_STATUS_TEXT:I = 0x7f0903eb

.field private static appLoader:Ljava/lang/ClassLoader;

.field private static final lastValues:[Ljava/lang/Object;

.field private static pollerStarted:Z

.field private static volatile topActivity:Landroid/app/Activity;


# direct methods
.method static constructor <clinit>()V
    .registers 1

    const/4 v0, 0x3

    .line 30
    new-array v0, v0, [Ljava/lang/Object;

    sput-object v0, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->lastValues:[Ljava/lang/Object;

    return-void
.end method

.method private constructor <init>()V
    .registers 1

    .line 327
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method

.method static synthetic access$000(IZ)Ljava/lang/Object;
    .registers 2
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 21
    invoke-static {p0, p1}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->buildBatteryInfo(IZ)Ljava/lang/Object;

    move-result-object p0

    return-object p0
.end method

.method static synthetic access$100()Ljava/lang/ClassLoader;
    .registers 1

    .line 21
    sget-object v0, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->appLoader:Ljava/lang/ClassLoader;

    return-object v0
.end method

.method static synthetic access$200(Ljava/lang/Object;Lcom/aclaniakea/colorosporttuning/PenState;)V
    .registers 2

    .line 21
    invoke-static {p0, p1}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->fixCardDisplay(Ljava/lang/Object;Lcom/aclaniakea/colorosporttuning/PenState;)V

    return-void
.end method

.method static synthetic access$300()Landroid/app/Activity;
    .registers 1

    .line 21
    sget-object v0, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->topActivity:Landroid/app/Activity;

    return-object v0
.end method

.method static synthetic access$302(Landroid/app/Activity;)Landroid/app/Activity;
    .registers 1

    .line 21
    sput-object p0, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->topActivity:Landroid/app/Activity;

    return-object p0
.end method

.method static synthetic access$400()V
    .registers 0

    .line 21
    invoke-static {}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->pollOnce()V

    return-void
.end method

.method private static buildBatteryInfo(IZ)Ljava/lang/Object;
    .registers 10
    .annotation system Ldalvik/annotation/Throws;
        value = {
            Ljava/lang/Exception;
        }
    .end annotation

    .line 169
    sget-object v0, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->appLoader:Ljava/lang/ClassLoader;

    const-string v1, "com.oplus.mydevices.domain.entities.device.BatteryInfo"

    const/4 v2, 0x0

    invoke-static {v1, v2, v0}, Ljava/lang/Class;->forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;

    move-result-object v0

    .line 170
    const-string v1, "com.oplus.mydevices.domain.entities.device.BatteryType"

    sget-object v3, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->appLoader:Ljava/lang/ClassLoader;

    invoke-static {v1, v2, v3}, Ljava/lang/Class;->forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;

    move-result-object v1

    .line 171
    const-class v3, Ljava/lang/Enum;

    invoke-virtual {v1, v3}, Ljava/lang/Class;->asSubclass(Ljava/lang/Class;)Ljava/lang/Class;

    move-result-object v3

    const-string v4, "SINGLE"

    invoke-static {v3, v4}, Ljava/lang/Enum;->valueOf(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;

    move-result-object v3

    const/4 v4, 0x3

    .line 172
    new-array v5, v4, [Ljava/lang/Class;

    aput-object v1, v5, v2

    sget-object v1, Ljava/lang/Integer;->TYPE:Ljava/lang/Class;

    const/4 v6, 0x1

    aput-object v1, v5, v6

    sget-object v1, Ljava/lang/Boolean;->TYPE:Ljava/lang/Class;

    const/4 v7, 0x2

    aput-object v1, v5, v7

    invoke-virtual {v0, v5}, Ljava/lang/Class;->getConstructor([Ljava/lang/Class;)Ljava/lang/reflect/Constructor;

    move-result-object v0

    if-gez p0, :cond_33

    const/4 p0, 0x0

    .line 173
    :cond_33
    invoke-static {p0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object p0

    invoke-static {p1}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;

    move-result-object p1

    new-array v1, v4, [Ljava/lang/Object;

    aput-object v3, v1, v2

    aput-object p0, v1, v6

    aput-object p1, v1, v7

    invoke-virtual {v0, v1}, Ljava/lang/reflect/Constructor;->newInstance([Ljava/lang/Object;)Ljava/lang/Object;

    move-result-object p0

    return-object p0
.end method

.method private static collectTextViews(Landroid/view/View;Ljava/lang/String;Ljava/util/List;)V
    .registers 5
    .annotation system Ldalvik/annotation/Signature;
        value = {
            "(",
            "Landroid/view/View;",
            "Ljava/lang/String;",
            "Ljava/util/List<",
            "Landroid/view/View;",
            ">;)V"
        }
    .end annotation

    if-nez p0, :cond_3

    return-void

    .line 298
    :cond_3
    instance-of v0, p0, Landroid/widget/TextView;

    if-eqz v0, :cond_1d

    .line 299
    move-object v0, p0

    check-cast v0, Landroid/widget/TextView;

    invoke-virtual {v0}, Landroid/widget/TextView;->getText()Ljava/lang/CharSequence;

    move-result-object v0

    if-eqz v0, :cond_1d

    .line 300
    invoke-interface {v0}, Ljava/lang/CharSequence;->toString()Ljava/lang/String;

    move-result-object v0

    invoke-virtual {p1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z

    move-result v0

    if-eqz v0, :cond_1d

    .line 301
    invoke-interface {p2, p0}, Ljava/util/List;->add(Ljava/lang/Object;)Z

    .line 304
    :cond_1d
    instance-of v0, p0, Landroid/view/ViewGroup;

    if-eqz v0, :cond_34

    .line 305
    check-cast p0, Landroid/view/ViewGroup;

    const/4 v0, 0x0

    .line 306
    :goto_24
    invoke-virtual {p0}, Landroid/view/ViewGroup;->getChildCount()I

    move-result v1

    if-ge v0, v1, :cond_34

    .line 307
    invoke-virtual {p0, v0}, Landroid/view/ViewGroup;->getChildAt(I)Landroid/view/View;

    move-result-object v1

    invoke-static {v1, p1, p2}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->collectTextViews(Landroid/view/View;Ljava/lang/String;Ljava/util/List;)V

    add-int/lit8 v0, v0, 0x1

    goto :goto_24

    :cond_34
    return-void
.end method

.method private static findCardRoot(Landroid/view/View;)Landroid/view/View;
    .registers 2

    :goto_0
    if-eqz p0, :cond_1b

    const v0, 0x7f0903b6

    .line 315
    invoke-virtual {p0, v0}, Landroid/view/View;->findViewById(I)Landroid/view/View;

    move-result-object v0

    if-eqz v0, :cond_c

    return-object p0

    .line 318
    :cond_c
    invoke-virtual {p0}, Landroid/view/View;->getParent()Landroid/view/ViewParent;

    move-result-object v0

    instance-of v0, v0, Landroid/view/View;

    if-eqz v0, :cond_1b

    .line 319
    invoke-virtual {p0}, Landroid/view/View;->getParent()Landroid/view/ViewParent;

    move-result-object p0

    check-cast p0, Landroid/view/View;

    goto :goto_0

    :cond_1b
    const/4 p0, 0x0

    return-object p0
.end method

.method private static fixCardDisplay(Ljava/lang/Object;Lcom/aclaniakea/colorosporttuning/PenState;)V
    .registers 9

    .line 215
    :try_start_0
    instance-of v0, p0, Landroid/view/View;

    if-nez v0, :cond_5

    return-void

    .line 218
    :cond_5
    check-cast p0, Landroid/view/View;

    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->findCardRoot(Landroid/view/View;)Landroid/view/View;

    move-result-object p0

    if-nez p0, :cond_e

    return-void

    .line 222
    :cond_e
    iget-boolean v0, p1, Lcom/aclaniakea/colorosporttuning/PenState;->connected:Z

    const v1, 0x7f0903b6

    .line 223
    invoke-virtual {p0, v1}, Landroid/view/View;->findViewById(I)Landroid/view/View;

    move-result-object v1

    const v2, 0x7f0903e9

    .line 224
    invoke-virtual {p0, v2}, Landroid/view/View;->findViewById(I)Landroid/view/View;

    move-result-object v2

    const v3, 0x7f0903eb

    .line 225
    invoke-virtual {p0, v3}, Landroid/view/View;->findViewById(I)Landroid/view/View;

    move-result-object v3

    const v4, 0x7f09009e

    .line 226
    invoke-virtual {p0, v4}, Landroid/view/View;->findViewById(I)Landroid/view/View;

    move-result-object p0

    const/4 v4, 0x0

    const/16 v5, 0x8

    if-eqz v1, :cond_3a

    if-eqz v0, :cond_35

    const/4 v6, 0x0

    goto :goto_37

    :cond_35
    const/16 v6, 0x8

    .line 228
    :goto_37
    invoke-virtual {v1, v6}, Landroid/view/View;->setVisibility(I)V

    :cond_3a
    if-eqz v2, :cond_43

    if-eqz v0, :cond_40

    const/16 v4, 0x8

    .line 231
    :cond_40
    invoke-virtual {v2, v4}, Landroid/view/View;->setVisibility(I)V

    .line 233
    :cond_43
    instance-of v1, v3, Landroid/widget/TextView;

    if-eqz v1, :cond_53

    .line 234
    check-cast v3, Landroid/widget/TextView;

    if-eqz v0, :cond_4e

    const-string v0, ""

    goto :goto_50

    :cond_4e
    const-string v0, "\u672a\u8fde\u63a5"

    :goto_50
    invoke-virtual {v3, v0}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V

    .line 236
    :cond_53
    instance-of v0, p0, Landroid/widget/TextView;

    if-eqz v0, :cond_79

    iget v0, p1, Lcom/aclaniakea/colorosporttuning/PenState;->battery:I

    if-ltz v0, :cond_79

    .line 237
    check-cast p0, Landroid/widget/TextView;

    iget p1, p1, Lcom/aclaniakea/colorosporttuning/PenState;->battery:I

    invoke-static {p1}, Ljava/lang/String;->valueOf(I)Ljava/lang/String;

    move-result-object p1

    invoke-virtual {p0, p1}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
    :try_end_66
    .catchall {:try_start_0 .. :try_end_66} :catchall_67

    goto :goto_79

    :catchall_67
    move-exception p0

    .line 240
    new-instance p1, Ljava/lang/StringBuilder;

    const-string v0, "CardBatteryHooks fixCard: "

    invoke-direct {p1, v0}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    invoke-virtual {p1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :cond_79
    :goto_79
    return-void
.end method

.method static install(Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;)V
    .registers 5

    .line 34
    iget-object v0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;

    sput-object v0, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->appLoader:Ljava/lang/ClassLoader;

    .line 35
    iget-object v0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;

    new-instance v1, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$1;

    invoke-direct {v1}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$1;-><init>()V

    const-string v2, "com.oplus.mydevices.domain.entities.cards.QuickCardDeviceData"

    const-string v3, "getBatteryMain"

    invoke-static {v0, v2, v3, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I

    .line 62
    iget-object v0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;

    new-instance v1, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$2;

    invoke-direct {v1}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$2;-><init>()V

    const-string v3, "getDisplayState"

    invoke-static {v0, v2, v3, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I

    .line 101
    iget-object v0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;

    new-instance v1, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$3;

    invoke-direct {v1}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$3;-><init>()V

    const-string v2, "com.oplus.mydevices.quickapp.homecard.view.BatteryLottieView"

    const-string v3, "setBatteryInfo"

    invoke-static {v0, v2, v3, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I

    .line 134
    iget-object v0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;

    new-instance v1, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$4;

    invoke-direct {v1}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$4;-><init>()V

    const-string v2, "com.oplus.mydevices.deviceui.devicecard.DeviceCardHomeActivity"

    const-string v3, "onResume"

    invoke-static {v0, v2, v3, v1}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I

    .line 142
    iget-object p0, p0, Lde/robv/android/xposed/callbacks/XC_LoadPackage$LoadPackageParam;->classLoader:Ljava/lang/ClassLoader;

    new-instance v0, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$5;

    invoke-direct {v0}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$5;-><init>()V

    const-string v1, "onPause"

    invoke-static {p0, v2, v1, v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->hookAll(Ljava/lang/ClassLoader;Ljava/lang/String;Ljava/lang/String;Lde/robv/android/xposed/XC_MethodHook;)I

    .line 150
    sget-boolean p0, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->pollerStarted:Z

    if-nez p0, :cond_5e

    const/4 p0, 0x1

    .line 151
    sput-boolean p0, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->pollerStarted:Z

    .line 152
    new-instance p0, Landroid/os/Handler;

    invoke-static {}, Landroid/os/Looper;->getMainLooper()Landroid/os/Looper;

    move-result-object v0

    invoke-direct {p0, v0}, Landroid/os/Handler;-><init>(Landroid/os/Looper;)V

    .line 153
    new-instance v0, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$6;

    invoke-direct {v0, p0}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks$6;-><init>(Landroid/os/Handler;)V

    invoke-virtual {p0, v0}, Landroid/os/Handler;->post(Ljava/lang/Runnable;)Z

    .line 165
    :cond_5e
    const-string p0, "CardBatteryHooks installed"

    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    return-void
.end method

.method private static pollOnce()V
    .registers 10

    const/4 v0, 0x0

    .line 177
    invoke-static {v0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->context(Ljava/lang/Object;)Landroid/content/Context;

    move-result-object v0

    if-nez v0, :cond_8

    return-void

    :cond_8
    const/4 v1, 0x0

    .line 185
    :try_start_9
    invoke-virtual {v0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v2

    const-string v3, "ipe_pencil_charging_state"

    invoke-static {v2, v3, v1}, Landroid/provider/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result v2
    :try_end_13
    .catchall {:try_start_9 .. :try_end_13} :catchall_14

    goto :goto_15

    :catchall_14
    const/4 v2, 0x0

    :goto_15
    const/4 v3, -0x1

    .line 190
    :try_start_16
    invoke-virtual {v0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v4

    const-string v5, "ipe_pencil_battery_level"

    invoke-static {v4, v5, v3}, Landroid/provider/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result v3
    :try_end_20
    .catchall {:try_start_16 .. :try_end_20} :catchall_20

    :catchall_20
    const/4 v4, 0x1

    .line 195
    :try_start_21
    invoke-virtual {v0}, Landroid/content/Context;->getContentResolver()Landroid/content/ContentResolver;

    move-result-object v0

    const-string v5, "lenovo_pen_link_connected"

    invoke-static {v0, v5, v4}, Landroid/provider/Settings$Global;->getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I

    move-result v0
    :try_end_2b
    .catchall {:try_start_21 .. :try_end_2b} :catchall_2c

    goto :goto_2e

    :catchall_2c
    nop

    const/4 v0, 0x1

    .line 199
    :goto_2e
    invoke-static {v2}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v5

    invoke-static {v3}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v6

    invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;

    move-result-object v7

    const/4 v8, 0x3

    new-array v8, v8, [Ljava/lang/Object;

    aput-object v5, v8, v1

    aput-object v6, v8, v4

    const/4 v5, 0x2

    aput-object v7, v8, v5

    .line 200
    sget-object v6, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->lastValues:[Ljava/lang/Object;

    .line 201
    aget-object v7, v6, v1

    if-eqz v7, :cond_69

    aget-object v9, v8, v1

    invoke-virtual {v9, v7}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result v7

    if-eqz v7, :cond_69

    aget-object v7, v8, v4

    aget-object v9, v6, v4

    invoke-virtual {v7, v9}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result v7

    if-eqz v7, :cond_69

    aget-object v7, v8, v5

    aget-object v9, v6, v5

    invoke-virtual {v7, v9}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z

    move-result v7

    if-nez v7, :cond_67

    goto :goto_69

    :cond_67
    const/4 v7, 0x0

    goto :goto_6a

    :cond_69
    :goto_69
    const/4 v7, 0x1

    .line 202
    :goto_6a
    sget-object v9, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->topActivity:Landroid/app/Activity;

    if-eqz v9, :cond_71

    .line 204
    invoke-static {v9, v2, v3, v0}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->refreshPenCard(Landroid/app/Activity;III)V

    :cond_71
    if-eqz v7, :cond_7f

    .line 207
    aget-object v0, v8, v1

    aput-object v0, v6, v1

    .line 208
    aget-object v0, v8, v4

    aput-object v0, v6, v4

    .line 209
    aget-object v0, v8, v5

    aput-object v0, v6, v5

    :cond_7f
    return-void
.end method

.method private static refreshPenCard(Landroid/app/Activity;III)V
    .registers 14

    .line 246
    :try_start_0
    invoke-virtual {p0}, Landroid/app/Activity;->getWindow()Landroid/view/Window;

    move-result-object p0

    invoke-virtual {p0}, Landroid/view/Window;->getDecorView()Landroid/view/View;

    move-result-object p0

    if-nez p0, :cond_b

    return-void

    .line 250
    :cond_b
    new-instance v0, Ljava/util/ArrayList;

    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V

    .line 251
    const-string v1, "Lenovo Tab Pen Pro"

    invoke-static {p0, v1, v0}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->collectTextViews(Landroid/view/View;Ljava/lang/String;Ljava/util/List;)V

    .line 253
    invoke-interface {v0}, Ljava/util/List;->iterator()Ljava/util/Iterator;

    move-result-object p0

    const/4 v0, 0x0

    const/4 v1, 0x0

    :cond_1b
    :goto_1b
    invoke-interface {p0}, Ljava/util/Iterator;->hasNext()Z

    move-result v2

    if-eqz v2, :cond_d1

    invoke-interface {p0}, Ljava/util/Iterator;->next()Ljava/lang/Object;

    move-result-object v2

    check-cast v2, Landroid/view/View;

    .line 254
    invoke-static {v2}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->findCardRoot(Landroid/view/View;)Landroid/view/View;

    move-result-object v2

    if-nez v2, :cond_2e

    goto :goto_1b

    :cond_2e
    const/4 v1, 0x1

    if-ne p3, v1, :cond_33

    const/4 v3, 0x1

    goto :goto_34

    :cond_33
    const/4 v3, 0x0

    :goto_34
    const v4, 0x7f0903b6

    .line 260
    invoke-virtual {v2, v4}, Landroid/view/View;->findViewById(I)Landroid/view/View;

    move-result-object v4

    const v5, 0x7f0903e9

    .line 261
    invoke-virtual {v2, v5}, Landroid/view/View;->findViewById(I)Landroid/view/View;

    move-result-object v5

    const v6, 0x7f0903eb

    .line 262
    invoke-virtual {v2, v6}, Landroid/view/View;->findViewById(I)Landroid/view/View;

    move-result-object v6

    const v7, 0x7f09009e

    .line 263
    invoke-virtual {v2, v7}, Landroid/view/View;->findViewById(I)Landroid/view/View;

    move-result-object v7

    const v8, 0x7f09009f

    .line 264
    invoke-virtual {v2, v8}, Landroid/view/View;->findViewById(I)Landroid/view/View;

    move-result-object v2

    const/16 v8, 0x8

    if-eqz v4, :cond_64

    if-eqz v3, :cond_5f

    const/4 v9, 0x0

    goto :goto_61

    :cond_5f
    const/16 v9, 0x8

    .line 266
    :goto_61
    invoke-virtual {v4, v9}, Landroid/view/View;->setVisibility(I)V

    :cond_64
    if-eqz v5, :cond_6d

    if-eqz v3, :cond_69

    goto :goto_6a

    :cond_69
    const/4 v8, 0x0

    .line 269
    :goto_6a
    invoke-virtual {v5, v8}, Landroid/view/View;->setVisibility(I)V

    .line 271
    :cond_6d
    instance-of v4, v6, Landroid/widget/TextView;

    if-eqz v4, :cond_7d

    .line 272
    check-cast v6, Landroid/widget/TextView;

    if-eqz v3, :cond_78

    const-string v3, ""

    goto :goto_7a

    :cond_78
    const-string v3, "\u672a\u8fde\u63a5"

    :goto_7a
    invoke-virtual {v6, v3}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V

    .line 274
    :cond_7d
    instance-of v3, v7, Landroid/widget/TextView;

    if-eqz v3, :cond_8c

    if-ltz p2, :cond_8c

    .line 275
    check-cast v7, Landroid/widget/TextView;

    invoke-static {p2}, Ljava/lang/String;->valueOf(I)Ljava/lang/String;

    move-result-object v3

    invoke-virtual {v7, v3}, Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V
    :try_end_8c
    .catchall {:try_start_0 .. :try_end_8c} :catchall_fe

    :cond_8c
    if-eqz v2, :cond_1b

    if-eqz p1, :cond_92

    const/4 v3, 0x1

    goto :goto_93

    :cond_92
    const/4 v3, 0x0

    .line 279
    :goto_93
    :try_start_93
    invoke-static {p2, v3}, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->buildBatteryInfo(IZ)Ljava/lang/Object;

    move-result-object v3

    .line 280
    const-string v4, "com.oplus.mydevices.quickapp.homecard.view.BatteryLottieView"

    sget-object v5, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->appLoader:Ljava/lang/ClassLoader;

    invoke-static {v4, v0, v5}, Ljava/lang/Class;->forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;

    move-result-object v4

    const-string v5, "setBatteryInfo"

    const-string v6, "com.oplus.mydevices.domain.entities.device.BatteryInfo"

    sget-object v7, Lcom/aclaniakea/colorosporttuning/CardBatteryHooks;->appLoader:Ljava/lang/ClassLoader;

    .line 281
    invoke-static {v6, v0, v7}, Ljava/lang/Class;->forName(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;

    move-result-object v6

    new-array v7, v1, [Ljava/lang/Class;

    aput-object v6, v7, v0

    invoke-virtual {v4, v5, v7}, Ljava/lang/Class;->getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;

    move-result-object v4

    .line 282
    new-array v5, v1, [Ljava/lang/Object;

    aput-object v3, v5, v0

    invoke-virtual {v4, v2, v5}, Ljava/lang/reflect/Method;->invoke(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
    :try_end_b8
    .catchall {:try_start_93 .. :try_end_b8} :catchall_ba

    goto/16 :goto_1b

    :catchall_ba
    move-exception v2

    .line 284
    :try_start_bb
    new-instance v3, Ljava/lang/StringBuilder;

    invoke-direct {v3}, Ljava/lang/StringBuilder;-><init>()V

    const-string v4, "CardBatteryHooks lottie: "

    invoke-virtual {v3, v4}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {v3, v2}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    invoke-virtual {v3}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object v2

    invoke-static {v2}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    goto/16 :goto_1b

    .line 288
    :cond_d1
    new-instance p0, Ljava/lang/StringBuilder;

    invoke-direct {p0}, Ljava/lang/StringBuilder;-><init>()V

    const-string v0, "CardBatteryHooks card refreshed connected="

    invoke-virtual {p0, v0}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0, p3}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string p3, " charging="

    invoke-virtual {p0, p3}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0, p1}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string p1, " level="

    invoke-virtual {p0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0, p2}, Ljava/lang/StringBuilder;->append(I)Ljava/lang/StringBuilder;

    const-string p1, " found="

    invoke-virtual {p0, p1}, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;

    invoke-virtual {p0, v1}, Ljava/lang/StringBuilder;->append(Z)Ljava/lang/StringBuilder;

    invoke-virtual {p0}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V
    :try_end_fd
    .catchall {:try_start_bb .. :try_end_fd} :catchall_fe

    goto :goto_110

    :catchall_fe
    move-exception p0

    .line 290
    new-instance p1, Ljava/lang/StringBuilder;

    const-string p2, "CardBatteryHooks refresh failed: "

    invoke-direct {p1, p2}, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V

    invoke-virtual {p1, p0}, Ljava/lang/StringBuilder;->append(Ljava/lang/Object;)Ljava/lang/StringBuilder;

    invoke-virtual {p1}, Ljava/lang/StringBuilder;->toString()Ljava/lang/String;

    move-result-object p0

    invoke-static {p0}, Lcom/aclaniakea/colorosporttuning/HookUtils;->log(Ljava/lang/String;)V

    :goto_110
    return-void
.end method
