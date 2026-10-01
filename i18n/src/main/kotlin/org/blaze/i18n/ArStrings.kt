package org.blaze.i18n

/**
 * Arabic (العربية) strings. The layout direction is applied by the shell (`LocalLayoutDirection`),
 * so this file only carries the translations. Where English branches on
 * singular/plural, Arabic collapses to "one" vs "count" forms - precise dual/plural tables can be
 * layered into the individual members later without touching any call site.
 */
object ArStrings : BlazeStrings {
    override val common = object : BlazeStrings.Common {
        override val ok = "حسناً"
        override val apply = "تطبيق"
        override val cancel = "إلغاء"
        override val browse = "تصفّح..."
        override val path = "المسار"
        override val name = "الاسم:"
        override val home = "الرئيسية"
        override val newFolder = "مجلد جديد"
        override val refresh = "تحديث"
        override val unknown = "غير معروف"
        override val copyDetails = "نسخ التفاصيل"
        override val close = "إغلاق"
        override val switchLight = "التبديل إلى السمة الفاتحة"
        override val switchDark = "التبديل إلى السمة الداكنة"
        override val views = "العرض"
        override val connected = "متصل"
        override val offline = "غير متصل"
        override val extensionIcon = "أيقونة الامتداد"
    }

    override val downloads = object : BlazeStrings.Downloads {
        override val title = "التنزيلات"
        override val emptyTitle = "لا توجد تنزيلات بعد"
        override val emptyDescription = "أضف تنزيلًا للبدء"
        override val emptyAction = "إضافة تنزيل"
        override val emptyPaste = "لصق الرابط من الحافظة"
        override val emptyDropHint = "تلميح: يمكنك أيضًا إفلات ملف ‎.torrent أو رابط magnet أو عنوان URL على النافذة."

        override val drop = object : BlazeStrings.Downloads.Drop {
            override val overlayTitle = "أفلت لإضافة تنزيلات"
            override val overlayUnsupportedTitle = "لا يمكن إفلات هذا هنا"
            override val hintTorrents = "ملفات التورنت وروابط magnet"
            override val hintLinks = "روابط التنزيل المباشر"
            override val hintLinkLists = "قوائم الروابط (‎.txt — رابط واحد في كل سطر)"
            override val hintPartials = "التنزيلات غير المكتملة (ملفات ‎.part والمجلدات)"
            override fun itemsMore(count: Int) = "+ $count أخرى"
            override fun queued(count: Int) =
                if (count == 1) "أُضيف مصدر واحد بانتظار التأكيد في نافذة الإضافة" else "$count مصادر بانتظار التأكيد في نافذة الإضافة"
            override fun rejected(names: String) = "لا يمكن تنزيل: $names"
            override val nothingUsable = "لا يوجد في ما أُفلت ما يمكن تنزيله"
        }
        override val searchPlaceholder = "بحث في التنزيلات…"
        override val clearSearch = "مسح البحث"

        override val filters = object : BlazeStrings.Downloads.Filters {
            override val all = "الكل"
            override val active = "النشطة"
            override val queued = "في الانتظار"
            override val paused = "المتوقفة"
            override val completed = "المكتملة"
            override val failed = "الفاشلة"
            override val seeding = "المتاحة للمشاركة"
        }

        override val sorting = object : BlazeStrings.Downloads.Sorting {
            override val name = "الاسم"
            override val size = "الحجم"
            override val speed = "السرعة"
            override val state = "الحالة"
            override val dateAdded = "تاريخ الإضافة"
            override val ascending = "تصاعدي"
            override val descending = "تنازلي"
        }

        override val columns = object : BlazeStrings.Downloads.Columns {
            override val name = "الاسم"
            override val progress = "التقدّم"
            override val size = "الحجم"
            override val speed = "السرعة"
            override val eta = "الوقت المتبقّي"
            override val status = "الحالة"
        }

        override val selection = object : BlazeStrings.Downloads.Selection {
            override val select = "تحديد"
            override val selectAll = "تحديد الكل"
            override val clearSelection = "إلغاء التحديد"
            override val deselect = "إلغاء التحديد"
            override fun count(n: Int) = "تم تحديد $n"
            override val pause = "إيقاف"
            override val resume = "استئناف"
            override val cancel = "إلغاء"
            override val remove = "إزالة"
            override fun pauseSelected(n: Int) = "إيقاف $n تنزيلات مؤقتًا"
            override fun resumeSelected(n: Int) = "استئناف $n تنزيلات"
            override fun cancelSelected(n: Int) = "إلغاء $n تنزيلات"
            override fun removeSelected(n: Int) = "إزالة $n تنزيلات"
            override val bulkRemoveTitle = "إزالة التنزيلات"
            override fun bulkRemoveMessage(n: Int) =
                "إزالة $n تنزيلات من القائمة؟ تبقى الملفات المنزَّلة على القرص."
        }

        override val toolbar = object : BlazeStrings.Downloads.Toolbar {
            override val add = "إضافة تنزيل"
            override val resumeAll = "استئناف الكل"
            override val pauseAll = "إيقاف الكل"
            override val clearCompleted = "مسح المكتملة"
            override val importPortable = "استيراد تنزيل محمول"
            override val snailMode = "وضع الحلزون — تقييد جميع التنزيلات إلى أقصى حد"
        }

        override val tableOptions = object : BlazeStrings.Downloads.TableOptions {
            override val menuTooltip = "خيارات الجدول"
            override val columns = "الأعمدة"
            override val resetColumnWidths = "إعادة ضبط عرض الأعمدة"
        }

        override val detailsPane = object : BlazeStrings.Downloads.DetailsPane {
            override val tabGeneral = "عام"
            override val tabFiles = "الملفات"
            override val emptyHint = "حدّد تنزيلًا لعرض التفاصيل"
            override val hide = "إخفاء التفاصيل"
            override val show = "إظهار التفاصيل"
            override val options = object : BlazeStrings.Downloads.DetailsPane.ToolWindowOptions {
                override val menuTooltip = "خيارات النافذة"
                override val groupTabs = "تجميع التبويبات"
                override val viewMode = "وضع العرض"
                override val moveTo = "نقل إلى"
                override val resize = "تغيير الحجم"
                override val dockPinned = "إرساء مثبّت"
                override val dockUnpinned = "إرساء غير مثبّت"
                override val undock = "إلغاء الإرساء"
                override val float = "عائم"
                override val window = "نافذة"
                override val leftTop = "يسار أعلى"
                override val leftBottom = "يسار أسفل"
                override val rightTop = "يمين أعلى"
                override val rightBottom = "يمين أسفل"
                override val bottomLeft = "أسفل اليسار"
                override val bottomRight = "أسفل اليمين"
                override val stretchLeft = "تمدّد إلى اليسار"
                override val stretchRight = "تمدّد إلى اليمين"
                override val stretchTop = "تمدّد إلى الأعلى"
                override val stretchBottom = "تمدّد إلى الأسفل"
                override val maximize = "تكبير النافذة"
            }
        }

        override val backgroundTasks = object : BlazeStrings.Downloads.BackgroundTasks {
            override val title = "المهام الخلفية"
            override val empty = "لا توجد تنزيلات قيد التشغيل"
            override fun runningCount(n: Int) = if (n == 1) "تنزيل واحد قيد التشغيل" else "$n تنزيلات قيد التشغيل"
            override fun footer(active: Int, queued: Int): String = buildString {
                if (active > 0) append("$active قيد التشغيل")
                if (queued > 0) {
                    if (isNotEmpty()) append("، ")
                    append("$queued في الانتظار")
                }
            }
        }

        override val dialogs = object : BlazeStrings.Downloads.Dialogs {
            override val addTitle = "إضافة تنزيل"
            override val addUrlPlaceholder = "https://…  أو  magnet:?xt=…"
            override val addFromFile = "إضافة من ملف"
            override val selectSourceFile = "اختر ملف Torrent أو ملف نصي"
            override val addDownload = "تنزيل"
            override val addAction = "إضافة"
            override val backToEdit = "العودة إلى التحرير"
            override val downloadSource = "مصدر التنزيل:"
            override val saveTo = "الحفظ في:"
            override val metadataResolved = "تم حل البيانات الوصفية:"
            override val url = "الرابط:"
            override val size = "الحجم:"
            override val fetchingMetadata = "جارٍ جلب البيانات الوصفية..."
            override val fetchingItems = "جارٍ جلب العناصر..."
            override val sourceWarning = "ليس رابط HTTP(S) ولا رابط magnet ولا ملف ‎.torrent؛ قد يفشل التنزيل."
            override val files = "الملفات:"
            override val selectAll = "تحديد الكل"
            override val deselectAll = "إلغاء تحديد الكل"
            override val itemsSelected = "عنصرًا محددًا"
            override val batchTitle = "إضافة تنزيلات دفعة"
            override val scheduleDelayLabel = "تأخير بدء التنزيل المجدول (دقائق):"
            override val scheduleDelayPlaceholder = "0 للبدء الفوري"

            override val authSectionTitle = "المصادقة (اختياري)"
            override val bearerTokenLabel = "رمز Bearer:"
            override val bearerTokenPlaceholder = "يُرسل كـ \"Authorization: Bearer …\""
            override val cookieLabel = "ملف تعريف الارتباط (Cookie):"
            override val cookiePlaceholder = "name=value; name2=value2"
            override val authHint =
                "تُستخدم لهذا التنزيل فقط؛ لا تُحفظ ولا تُعرض مجددًا ولا تُكتب في السجل."

            override val pasteFromClipboard = "لصق من الحافظة"
            override val clearSource = "مسح"
            override val advancedOptions = "خيارات متقدمة"
            override val sourceKindMagnet = "رابط magnet"
            override val sourceKindTorrent = "ملف Torrent"
            override val sourceKindHttp = "رابط HTTP"
            override val emptyLinkFile = "لم تُعثر على روابط في هذا الملف"
            override fun linkCount(count: Int) = "$count روابط"
            override fun sourceFileReadFailed(reason: String?) =
                "تعذّرت قراءة الملف${reason?.let { ": $it" } ?: ""}"
            override fun freeSpace(size: String) = "المساحة المتاحة: $size"
            override val destinationWillCreate = "المجلد غير موجود بعد وسيتم إنشاؤه"
            override val destinationNotDirectory = "هذا المسار ملف وليس مجلدًا"
            override val destinationNotWritableHint = "لا يمكن الكتابة إلى هذا المجلد"
            override val destinationInvalidPath = "مسار غير صالح"

            override val removeTitle = "إزالة التنزيل"
            override fun removeMessage(name: String) = "إزالة $name؟"
            override val removeOnly = "إزالة من القائمة فقط"
            override val removeOnlyHint = "يبقى الملف المنزَّل على القرص."
            override val removeAndDelete = "إزالة من القائمة وحذف الملف من القرص"
            override val removeAndDeleteHint = "يُحذف الملف نهائيًا ولا يمكن استعادته."
            override val dontAskAgain = "عدم السؤال مرة أخرى"
            override val removeAction = "إزالة"
            override val deleteAction = "حذف"

            override val conflictTitle = "تعارض في الملفات"
            override fun conflictMessage(name: String) = "الملف «$name» موجود بالفعل في الوجهة."
            override val overwriteAction = "استبدال"
            override val renameAction = "إعادة تسمية"
            override val skipAction = "تخطي"

            override val handlerChoiceTitle = "اختيار معالج الروابط"
            override val handlerChoicePrompt = "أكثر من معالج يستطيع معالجة هذا الرابط؛ أيها نستخدم؟"
            override val resolveAction = "تحليل"
            override val resolvingLink = "جارٍ تحليل الرابط..."
            override val noHandlerForLink = "لا يوجد معالج مثبّت يستطيع معالجة هذا الرابط."
            override fun handlerWillBeUsed(name: String) =
                "سيُحلّل باستخدام امتداد $name."
            override fun handlerWillAsk(count: Int) =
                "$count امتدادات تستطيع معالجة هذا الرابط — سيُطلب منك اختيار أحدها."

            override val resolvedLinksTitle = "اختر الروابط المراد تنزيلها"
            override val resolvedLinksPrompt =
                "تحتوي هذه الصفحة على أكثر من رابط قابل للتنزيل. اختر ما تريده — اختيار عدة روابط يراجعها كدفعة."
            override val pickLinksAction = "تنزيل"
            override val downloadAllAction = "تنزيل الكل"
            override val noLinksResolved = "لم يجد المعالج أي رابط قابل للتنزيل في هذه الصفحة."
            override fun selectedLinkCount(count: Int, total: Int) = "$count من $total محدّدة"

            override val plainPage = object : BlazeStrings.Downloads.Dialogs.PlainPage {
                override val title = "لا يوجد معالج لهذا الرابط"
                override fun message(url: String) =
                    "لا يستطيع أي امتداد مثبّت تفكيك هذا الرابط، لذا لا يمكن لـ Blaze العثور على ملف داخله: $url"
                override val detail =
                    "إذا تابعت فسيحفظ Blaze ما يردّه الموقع على هذا الرابط تمامًا — الصفحة نفسها، " +
                        "أي ملف HTML في العادة، وليس الملف الذي تشير إليه الصفحة."
                override val confirm = "تنزيل الصفحة على أي حال"
            }

            override val details = object : BlazeStrings.Downloads.Dialogs.Details {
                override val title = "تفاصيل التنزيل"
                override val name = "الاسم"
                override val status = "الحالة"
                override val location = "الحفظ في"
                override val source = "رابط المصدر"
                override val size = "الحجم"
                override val speed = "السرعة"
                override val eta = "الوقت المتبقي"
                override val peers = "الأقران"
                override val added = "تاريخ الإضافة"
                override val scheduled = "مجدول للبدء"
                override val files = "الملفات"
                override val error = "خطأ"
                override val resumeSupport = "إيقاف واستئناف"
            }

            override val pauseRisk = object : BlazeStrings.Downloads.Dialogs.PauseRisk {
                override val title = "إيقاف هذا التنزيل؟"
                override fun message(name: String) = "إيقاف $name؟"
                override fun detail(percent: Int) = if (percent > 0) {
                    "هذا الخادم لا يستطيع متابعة تنزيل متقطع، لذا سيؤدي الإيقاف إلى التخلص من " +
                        "نسبة $percent% المنزَّلة بالفعل وسيتعين البدء من الصفر."
                } else {
                    "هذا الخادم لا يستطيع متابعة تنزيل متقطع، لذا سيبدأ من الصفر."
                }
                override val confirm = "الإيقاف على أي حال"
            }

            override val clearCompleted = object : BlazeStrings.Downloads.Dialogs.ClearCompleted {
                override val title = "مسح التنزيلات المكتملة"
                override fun message(count: Int) = if (count == 1) {
                    "إزالة تنزيل مكتمل واحد من القائمة؟"
                } else {
                    "إزالة $count تنزيلات مكتملة من القائمة؟"
                }
                override val detail = "تبقى الملفات نفسها على القرص؛ ما يغادر القائمة هو مدخلاتها فقط."
                override fun seedingDetail(count: Int) = if (count == 1) {
                    "واحد منها ما زال يشارك؛ مسحه سيوقف مشاركة هذا الملف."
                } else {
                    "$count منها ما زالت تشارك؛ مسحها سيوقف تلك المشاركات."
                }
                override val confirm = "مسح القائمة"
            }

            override val destinationNotWritable = "لا يمكن الكتابة إلى مجلد الحفظ. عُد واختر مجلدًا آخر."
            override fun notEnoughSpace(needed: String, free: String) =
                "لا توجد مساحة كافية على هذا القرص: مطلوب $needed ومتاح $free."
            override val sourceCannotResume =
                "هذا الخادم لا يستطيع متابعة تنزيل متقطع؛ إذا أوقفته فسيبدأ من الصفر."
            override val metadataUnresolvedTorrent =
                "لم تُحل تفاصيل الـ torrent بعد — ستظهر في القائمة بمجرد استجابة الأقران."
            override fun httpProbeFailed(url: String) =
                "تعذّر الوصول إلى هذا الرابط: $url. قد يكون غير متصل أو مكتوبًا بشكل خاطئ."
        }

        override val status = object : BlazeStrings.Downloads.Status {
            override val downloading = "جارٍ التنزيل"
            override val paused = "متوقف"
            override val completed = "مكتمل"
            override val queued = "في الانتظار"
            override val error = "خطأ"
            override val cancelling = "جارٍ الإلغاء"
            override val seeding = "مشاركة"
            override val scheduled = "مجدول"
        }

        override val priority = object : BlazeStrings.Downloads.Priority {
            override val idle = "خامل"
            override val low = "منخفضة"
            override val normal = "عادية"
            override val high = "عالية"
            override val label = "الأولوية"
        }

        override fun progress(downloaded: String, total: String) = "$downloaded / $total"
        override fun completedCount(count: Int) = if (count == 1) "اكتمل تنزيل واحد" else "$count تنزيلات مكتملة"
        override fun peers(count: Int) = if (count == 1) "قرن واحد" else "$count قرون"
        override fun speed(speed: String) = "$speed/ث"
        override fun filesCount(count: Int) = if (count == 1) "ملف واحد" else "$count ملفات"

        override val actions = object : BlazeStrings.Downloads.Actions {
            override val pause = "إيقاف"
            override val resume = "استئناف"
            override val retry = "إعادة المحاولة"
            override val cancel = "إلغاء"
            override val remove = "إزالة"
            override val showFiles = "إظهار الملفات"
            override val openFile = "فتح"
            override val showInFolder = "إظهار في المجلد"
            override val openSourceLink = "فتح رابط المصدر في المتصفح"
            override val copyLink = "نسخ رابط التنزيل"
            override val copyFilePath = "نسخ موقع الملف"
            override val showDetails = "إظهار التفاصيل"
            override val reDownload = "إعادة التنزيل"
            override val clear = "مسح من القائمة"
        }

        override val portable = object : BlazeStrings.Downloads.Portable {
            override val indicator = "الاستئناف المحمول مفعّل"
            override val dialogTitle = "استئناف تنزيل محمول"
            override fun kindHttp(name: String) = "تنزيل HTTP — $name"
            override fun kindTorrent(name: String) = "تنزيل Torrent — $name"
            override val source = "المصدر"
            override val expectedSize = "الحجم المتوقع"
            override val available = "المُنزَّل بالفعل"
            override val saveLocation = "الحفظ في"
            override val resumable = "قابل للاستئناف"
            override val staleWarning = "قد تكون هذه الحالة قديمة؛ ستُعاد مراجعة البيانات الموجودة قبل الاستئناف."
            override val credentialsNote = "إذا كان المصدر يتطلب تسجيل الدخول فوفّر بيانات الاعتماد على هذا الجهاز للاستئناف."
            override val importAction = "استئناف التنزيل"
            override fun notPortable(path: String) = "«$path» ليس تنزيلًا محمولًا من Blaze."
            override val imported = "أُضيف التنزيل المحمول إلى قائمة الانتظار."
        }

        override val advisories = object : BlazeStrings.Downloads.Advisories {
            override val cannotResume = "لا يمكن الاستئناف"
            override val waitingForPeers = "في انتظار الأقران"
            override val fileMissing = "الملف مفقود"
            override val resumeSupported = "يمكن استئنافه من حيث توقف"
            override val titleCompleted = "اكتمل التنزيل"
            override val titleFailed = "فشل التنزيل"
            override val titleRestarted = "أُعيد بدء التنزيل"
            override fun completed(name: String) = "انتهى تنزيل $name."
            override fun failed(name: String, reason: String) = "فشل $name: $reason"
            override fun restarted(name: String, percent: Int) =
                "لم يتمكّن $name من المتابعة من نسبة $percent%، لذا عاد إلى نقطة البداية."
            override fun resumeStartsOver(name: String) =
                "خادم $name لا يدعم المتابعة، لذا سيبدأ الاستئناف من الصفر."
            override fun pauseAllCosts(count: Int) = if (count == 1) {
                "تنزيل واحد لا يستطيع خادمه متابعته؛ إيقافه سيتلف تقدّمه."
            } else {
                "$count تنزيلات لا تستطيع خوادمها متابعها؛ إيقافها سيتلف تقدّمها."
            }
            override fun duplicateSource(name: String) = "$name موجود بالفعل في قائمة التنزيلات."
            override fun removeInProgress(percent: Int) =
                "لم يكتمل سوى $percent% من هذا التنزيل؛ إزالته ستؤدي إلى تجاهل البيانات."
            override fun alertCannotResume(count: Int) = if (count == 1) {
                "لا يمكن استئناف تنزيل واحد"
            } else {
                "لا يمكن استئناف $count تنزيلات"
            }
            override fun alertWaitingPeers(count: Int) = if (count == 1) {
                "تنزيل واحد ينتظر الأقران"
            } else {
                "$count تنزيلات تنتظر الأقران"
            }
        }
    }

    override val filePicker = object : BlazeStrings.FilePicker {
        override val titleFolder = "اختيار مجلد"
        override val titleFile = "اختيار ملف"
        override val home = "الرئيسية"
        override val newFolder = "مجلد جديد"
        override val refresh = "تحديث"
        override val showHidden = "إظهار الملفات المخفية"
        override val hideHidden = "إخفاء الملفات المخفية"
        override val pathPlaceholder = "المسار"
        override val hintFolder = "اختر مجلدًا من الشجرة أو اكتب المسار أعلاه."
        override val hintFile = "اختر ملفًا من الشجرة أو اكتب المسار أعلاه."
        override fun supportedTypes(extensions: String) = "الأنواع المدعومة: $extensions"

        override val newFolderTitle = "مجلد جديد"
        override val newFolderName = "الاسم:"
        override fun newFolderCreatedIn(parent: String) = "سيُنشأ داخل $parent"
        override val newFolderError = "تعذّر إنشاء المجلد"

        override val errorInvalidPath = "مسار غير صالح"
        override val errorPathNotExists = "المسار غير موجود"
        override val errorNotAFolder = "ليس مجلدًا"
        override val errorUnsupportedFileType = "نوع الملف هذا غير مدعوم"
        override val errorInvalidName = "اسم غير صالح"
        override val errorIllegalCharacters = "الاسم يحتوي على أحرف غير مسموح بها"
        override val errorAlreadyExists = "يوجد ملف أو مجلد بهذا الاسم بالفعل"

        override val collapse = "طيّ"
        override val expand = "توسيع"
    }

    override val statusSummary = object : BlazeStrings.StatusSummary {
        override val ready = "جاهز"
        override fun downloading(count: Int) = "$count جارٍ التنزيل"
        override fun queued(count: Int) = "$count في الانتظار"
        override fun paused(count: Int) = "$count متوقفة"
        override fun failed(count: Int) = "$count فاشلة"
        override fun snailMode(rate: String) = "وضع الحلزون · $rate"
    }

    override val tray = object : BlazeStrings.Tray {
        override val show = "إظهار النافذة"
        override val hide = "إخفاء النافذة"
        override val pauseAll = "إيقاف جميع التنزيلات"
        override val resumeAll = "استئناف جميع التنزيلات"
        override val quit = "إنهاء Blaze"
        override val enableSnailMode = "تفعيل وضع الحلزون"
        override val disableSnailMode = "تعطيل وضع الحلزون"
    }

    override val menu = object : BlazeStrings.Menu {
        override val mainMenu = "القائمة الرئيسية"
        override val search = "بحث"
        override val searchEverywhere = "البحث في كل مكان…"
        override val newDownload = "تنزيل جديد…"
        override val settings = "الإعدادات…"
        override val pauseAll = "إيقاف الكل"
        override val resumeAll = "استئناف الكل"
        override val toggleDetails = "إظهار/إخفاء التفاصيل"
        override val about = "حول Blaze"
        override val exit = "خروج"
        override val speedLimit = "حد السرعة"
        override val fullSpeed = "السرعة الكاملة"
        override val snailMode = "وضع الحلزون"
        override val aboutTitle = "حول Blaze"
        override val aboutBody = "مدير تنزيلات سريع وعصري."
    }

    override val search = object : BlazeStrings.Search {
        override val placeholder = "ابحث في التنزيلات والإعدادات والإجراءات…"
        override val tabAll = "الكل"
        override val tabDownloads = "التنزيلات"
        override val tabActions = "الإجراءات"
        override val tabSettings = "الإعدادات"
        override val typeToSearch = "اكتب للبحث"
        override val nothingFound = "لا نتائج"
        override val sectionDownloads = "التنزيلات"
        override val sectionActions = "الإجراءات"
        override val sectionSettings = "الإعدادات"
    }

    override val extensions = object : BlazeStrings.Extensions {
        override val title = "الامتدادات"
        override val subtitle = "تحوّل معالجات الروابط روابط الصفحات إلى تنزيلات مباشرة؛ التغييرات هنا تسري فورًا."
        override val installAction = "تثبيت امتداد..."
        override val reloadAction = "إعادة تحميل"
        override val removeAction = "إزالة"
        override val optionsAction = "خيارات..."
        override val emptyTitle = "لا توجد امتدادات مثبّتة"
        override val emptyDescription = "المعالجات المضمّنة متاحة دائمًا. ثبّت ملف jar لمعالج لتعليم Blaze مواقع جديدة، أو أدرها كلها من الإعدادات."
        override val builtinTag = "مضمّن"
        override val pluginTag = "امتداد"
        override val enabledLabel = "مفعّل"
        override fun handlesSites(count: Int) = if (count == 1) "نوع روابط واحد مدعوم" else "$count معالجات متاحة"
        override fun removeConfirm(name: String) =
            "إزالة امتداد $name؟ يُحذف ملف jar من مجلد الامتدادات؛ التنزيلات المُدرجة مسبقًا لا تتأثر."
        override val removeConfirmTitle = "إزالة الامتداد"
    }

    override val settings = object : BlazeStrings.Settings {
        override val title = "الإعدادات"
        override val placeholder = "ستظهر إعدادات التطبيق هنا."
        override val searchPlaceholder = "بحث في الإعدادات…"
        override val clearSearch = "مسح البحث"
        override val searchNoMatches = "لا توجد إعدادات مطابقة"
        override val generalCategory = "عام"
        override val downloadsCategory = "التنزيلات"
        override val filesCategory = "الملفات"
        override val logsCategory = "السجلات"
        override val shortcutsCategory = "الاختصارات"
        override val concurrencyHeader = "التزامن والترتيب"
        override val maxConcurrentDownloadsLabel = "الحد الأقصى للتنزيلات المتزامنة"
        override val maxConcurrentDownloadsDesc = "يحدد عدد التنزيلات التي يمكن أن تعمل بنشاط في الوقت نفسه؛ التنزيلات التي تتجاوز هذا الحد تبقى في قائمة الانتظار حتى يتوفر موضع."
        override val maxConnectionsLabel = "الحد الأقصى للاتصالات لكل تنزيل"
        override val maxConnectionsDesc = "عدد اتصالات/شرائح HTTP أو تدفقات torrent المتزامنة المخصصة لكل مهمة تنزيل."
        override val defaultPriorityLabel = "الأولوية الافتراضية للتنزيلات الجديدة"
        override val defaultPriorityDesc = "مدى إلحاح التنزيل المضاف حديثًا مقارنة بغيره؛ الأولويات الأعلى تبدأ أولاً حين يتوفر موضع وتحصل على نصيب أكبر من الاتصالات."
        override val bandwidthHeader = "حد النطاق الترددي"
        override val speedLimitEnabledLabel = "تقييد سرعة التنزيل عالميًا"
        override val speedLimitLabel = "حد سرعة التنزيل (KB/s)"
        override val retryHeader = "إعادة المحاولة التلقائية"
        override val autoRetryLabel = "إعادة محاولة التنزيلات الفاشلة تلقائيًا"
        override val maxRetriesLabel = "الحد الأقصى لإعادة المحاولة"
        override val retryDelayLabel = "تأخير إعادة المحاولة (ثوانٍ)"
        override val exponentialBackoffLabel = "زيادة التأخير بشكل أُسّي بين المحاولات"
        override val networkHeader = "الشبكة والبروتوكول"
        override val userAgentLabel = "وكيل HTTP (User-Agent)"
        override val userAgentDesc = "يُرسل مع كل طلب HTTP؛ اتركه فارغًا لاستخدام الافتراضي المضمّن."
        override val maxRedirectsLabel = "الحد الأقصى لإعادة التوجيه"
        override val maxRedirectsDesc = "عدد عمليات إعادة التوجيه في HTTP التي يجب اتباعها قبل التوقف؛ ضع 0 لتعطيل إعادة التوجيه."
        override val torrentHeader = "Torrent"
        override val maxPeerConnectionsLabel = "الحد الأقصى لاتصالات الأقران"
        override val maxPeerConnectionsDesc = "الحد الأعلى للاتصالات المتزامنة مع الأقران لكل عميل torrent."
        override val seedingEnabledLabel = "الاستمرار في المشاركة بعد اكتمال التنزيل"
        override val seedTimeLimitLabel = "مدة المشاركة (دقائق)"
        override val unlimitedSeedingLabel = "مشاركة بلا حد (حتى الإيقاف يدويًا)"
        override val seedRatioLimitLabel = "حد نسبة المشاركة"
        override val seedRatioLimitDesc = "تتوقف المشاركة عندما تُرسل بيانات بنسبة محددة من حجم التنزيل؛ 0 يعني بلا حد."
        override val torrentFilePreviewLabel = "معاينة روابط ‎.torrent قبل الإضافة"
        override val torrentFilePreviewDesc = "عند إضافة رابط http(s) إلى ملف ‎.torrent يقوم Blaze بتنزيله وعرض الملفات بداخله ثم بدء التنزيل كتورنت محلي. هذا الخيار معطّل افتراضيًا: تُحلَّل هذه الروابط عبر الشبكة بدلًا من ذلك."
        override val snailModeEnabledLabel = "وضع الحلزون — إبطاء جميع التنزيلات إلى أقصى حد"
        override val snailSpeedLabel = "سرعة الحلزون (KB/s)"
        override val snailSpeedDesc = "سقف على مستوى المحرك يُطبَّق أثناء تفعيل وضع الحلزون؛ يتجاوز حد السرعة العادي لإتاحة اتصالك لأعمال أخرى."
        override val appearanceHeader = "المظهر"
        override val themeLabel = "السمة"
        override val languageLabel = "اللغة"
        override val themeSystemOption = "متابعة النظام"
        override val themeLightOption = "فاتحة"
        override val themeDarkOption = "داكنة"
        override val startupHeader = "سلوك بدء التشغيل وقائمة الانتظار"
        override val resumeOnStartupLabel = "استئناف التنزيلات تلقائيًا عند بدء تشغيل التطبيق"
        override val startQueuedOnStartupLabel = "بدء التنزيلات المنتظرة تلقائيًا عند بدء تشغيل التطبيق"
        override val systemIntegrationHeader = "التكامل مع النظام"
        override val trayEnabledLabel = "إظهار أيقونة في درج النظام"
        override val trayEnabledDesc = "يبقي Blaze متاحًا في الخلفية؛ غير متاح على أنظمة الشاشات التي لا تملك درجًا."
        override val minimizeToTrayLabel = "إخفاء النافذة إلى الدرج عند الإغلاق"
        override val runAtStartupLabel = "تشغيل Blaze مع بدء النظام"
        override val runAtStartupDesc = "يسجّل Blaze للعمل تلقائيًا عند تسجيل دخولك."
        override val clipboardMonitoringLabel = "مراقبة الحافظة عن الروابط المنزَّلة"
        override val clipboardMonitoringDesc = "عند نسخ رابط ويب أو رابط magnet أو مسار ملف ‎.torrent سيعرضه Blaze تنزيلًا جديدًا؛ لا يُضاف شيء حتى تؤكد."
        override val securityHeader = "الأمان"
        override val approveExternalClientsLabel = "اطلب الإذن قبل السماح لتطبيقات أخرى بالتحكم في Blaze"
        override val approveExternalClientsDesc = "يوفّر واجهة API المحلية للتطبيقات الموثوقة إمكانية إضافة التنزيلات وإدارتها؛ عند التفعيل يوقف Blaze أول طلب من تطبيق جديد ويسألك عن السماح له، وعند التعطيل يمر أي تطبيق يملك رمز الوصول دون سؤال."
        override val rememberedClientsLabel = "التطبيقات المسموح لها"
        override val rememberedClientsEmpty = "لا توجد تطبيقات مسموح لها دون سؤال؛ كل تطبيق تسمح له هنا يتحكم في Blaze بصمت."
        override val revokeAccessAction = "سحب الإذن"
        override val approveExtensionsLabel = "اطلب الإذن قبل تثبيت أو تحميل الامتدادات"
        override val approveExtensionsDesc = "امتدادات معالجات الروابط والسمات هي أكواد طرف ثالث تعمل داخل Blaze؛ عند التفعيل تؤكد كل ملف jar غير معتمد قبل تنفيذ شيفرته، وعند التعطيل تُحمَّل الملفات الموضوعة في مجلدات الامتدادات دون سؤال."
        override val trustedExtensionsLabel = "الامتدادات الموثوقة"
        override val trustedExtensionsEmpty = "لا توجد امتدادات موثوقة للتحميل دون سؤال."
        override val forgetAction = "إزالة الثقة"
        override val trustedKindHandler = "معالج روابط"
        override val trustedKindTheme = "سمة"
        override val destinationHeader = "وجهات التنزيل"
        override val defaultDownloadDirLabel = "مجلد التنزيل الافتراضي"
        override val askWhereToSaveLabel = "السؤال عن موضع الحفظ قبل بدء كل تنزيل"
        override val fileConflictsHeader = "تعارضات الملفات"
        override val fileConflictBehaviorLabel = "عندما يكون ملف موجودًا بالفعل في الوجهة"
        override val askOption = "السؤال عن الإجراء"
        override val overwriteOption = "استبدال الملف الموجود"
        override val skipOption = "تخطي التنزيل"
        override val renameOption = "إعادة تسمية الملف تلقائيًا"
        override val validationPositiveNumber = "يجب أن يكون رقمًا موجبًا أكبر من 0"
        override val validationNonNegativeNumber = "يجب أن يكون رقمًا غير سالب (0 أو أكثر)"

        override val logging = object : BlazeStrings.Settings.Logging {
            override val header = "التسجيل"
            override val levelLabel = "مستوى السجل"
            override val levelDesc = "يحدد مقدار التفاصيل التي يكتبها Blaze في السجلات؛ مستوى Debug و Trace مفيدان أساسًا عند تشخيص المشكلات."
            override val offOption = "إيقاف"
            override val errorOption = "الأخطاء"
            override val warnOption = "التحذيرات"
            override val infoOption = "معلومات"
            override val debugOption = "تنقيح"
            override val traceOption = "تتبع"
            override val fileLoggingLabel = "كتابة السجلات إلى ملفات"
            override val fileLoggingDesc = "عند التعطيل تُطبع السجلات في الطرفية فقط (تظهر عند تشغيل Blaze من سطر الأوامر)."
            override val filesHeader = "ملفات السجل"
            override val logDirLabel = "مجلد السجلات"
            override val logDirDesc = "توجد السجلات الحالية والمدوّرة هنا؛ تُحتفظ الملفات المدوّرة 7 أيام وبحد أقصى 100 ميغابايت إجمالًا."
            override val openFolderAction = "فتح المجلد…"
        }

        override val handlers = object : BlazeStrings.Settings.Handlers {
            override val category = "معالجات الروابط"
            override val supportedSitesHeader = "المواقع المدعومة"
            override val supportedSitesDesc = "تحوّل المعالجات روابط الصفحات إلى روابط تنزيل مباشرة."
            override val alwaysAskLabel = "دائمًا اسأل عن المعالج المراد استخدامه عند تطابق أكثر من واحد"
            override val alwaysAskDesc = "عند التعطيل يُستخدم أول معالج متطابق تلقائيًا؛ المعالج الوحيد المتطابق يُستخدم دائمًا دون سؤال."
            override val extensionsDirHeader = "الامتدادات"
            override val extensionsDirLabel = "مجلد الامتدادات"
            override val extensionsDirDesc = "ملفات jar للمعالجات تُوضع في هذا المجلد وتُحمَّل عند بدء التشغيل."
            override val installAction = "تثبيت امتداد..."
            override val reloadAction = "إعادة تحميل"
            override val removeAction = "إزالة"
            override val emptyHandlers = "لا توجد معالجات مثبّتة."
            override val builtinTag = "مضمّن"
            override val pluginTag = "امتداد"
            override val optionsAction = "خيارات..."
            override fun optionsTitle(name: String) = "خيارات امتداد $name"
            override val priorityLabel = "أولوية المعالج"
            override val priorityDesc = "عند تطابق عدة معالجات مع رابط، يُستخدم صاحب الأولوية الأعلى أولًا."
            override val restoreDefaultsAction = "استعادة الافتراضيات"
            override val requestTimeoutLabel = "مهلة طلب المعالج (ثوانٍ)"
            override val requestTimeoutDesc = "المدة التي يُسمح لمعالج الروابط بقضائها في جلب صفحة قبل أن يتخلى Blaze عنها."
            override val installRefused = "تم إلغاء التثبيت: لم تسمح للامتداد بالتنفيذ."
            override fun installFailed(reason: String) = "فشل التثبيت: $reason"
        }

        override val themes = object : BlazeStrings.Settings.Themes {
            override val category = "السمات"
            override val colorThemeHeader = "سمة الألوان"
            override val colorThemeDesc = "اختر سمة لتعديل اللون المميز والأسطح في Blaze؛ أضف المزيد عبر تثبيت امتدادات السمات."
            override val activeRadio = "النشطة"
            override val themesDirHeader = "امتدادات السمات"
            override val themesDirLabel = "مجلد السمات"
            override val themesDirDesc = "ملفات jar للسمات تُوضع في هذا المجلد وتُحمَّل عند بدء التشغيل."
            override val installAction = "تثبيت سمة..."
            override val reloadAction = "إعادة تحميل"
            override val removeAction = "إزالة"
            override val builtinTag = "مضمّن"
            override val pluginTag = "امتداد"
            override val optionsAction = "خيارات"
            override fun optionsTitle(name: String) = "خيارات سمة $name"
            override val restoreDefaultsAction = "استعادة الافتراضيات"
            override val colorFormatHint = "لون سداسي، ‎#RRGGBB أو ‎#AARRGGBB"
            override fun themeByline(author: String, version: String): String =
                listOfNotNull(
                    author.takeIf { it.isNotBlank() }?.let { "بواسطة $it" },
                    version.takeIf { it.isNotBlank() }?.let { "الإصدار $it" },
                ).joinToString(" · ")
            override val chromeHeader = "هيكل النافذة"
            override val ambientGlowLabel = "توهّج محيط بشريط العنوان"
            override val ambientGlowDesc = "توهّج ناعم بلون التمييز في أعلى يسار إطار النافذة، خلف شريط العنوان والشريط الجانبي."
        }

        override val notifications = object : BlazeStrings.Settings.Notifications {
            override val category = "الإشعارات"
            override val header = "إشعارات سطح المكتب"
            override val enabledLabel = "نشر أحداث التنزيل على سطح المكتب"
            override val enabledDesc = "رسوم منبثقة للنظام وعناصر في مركز الإشعارات، إضافةً إلى التنبيهات المعروضة داخل النافذة."
            override val completedLabel = "اكتمل تنزيل"
            override val failedLabel = "فشل تنزيل"
            override val restartedLabel = "تنزيل غير مكتمل اضطر للبدء من جديد"
            override val attentionHeader = "متى يجب أن يُبلّغ Blaze"
            override val whileInSightLabel = "أشعر أيضًا عندما تكون نافذة Blaze ظاهرة ومركَّزة"
            override val whileInSightDesc = "عند التعطيل تُنشر الإشعارات فقط عندما يكون Blaze مخفيًا في الدرج أو في الخلفية — فالقائمة تعرض ما يحدث بالفعل أثناء النظر إليها."
            override val unsupportedNote = "لا يوفّر هذا السطح أي قناة إشعارات، لذلك لا يستطيع Blaze نشر إشعارات نظام."
        }

        override val shortcuts = object : BlazeStrings.Settings.Shortcuts {
            override val category = "الاختصارات"
            override val header = "اختصارات لوحة المفاتيح"
            override val description = "أعد ربط أوامر التطبيق؛ يحتاج الاختصار إلى معدِّل (Ctrl/⌘ أو Alt) ما لم يكن مفتاحًا لا يكتب نصًا مثل Enter أو Delete."
            override val newDownload = "تنزيل جديد"
            override val searchEverywhere = "البحث الشامل"
            override val focusListSearch = "تحويل التركيز إلى بحث القائمة"
            override val toggleDetails = "إظهار/إخفاء لوحة التفاصيل"
            override val selectAll = "تحديد الكل"
            override val togglePause = "إيقاف/استئناف"
            override val remove = "إزالة تنزيل"
            override val openSelected = "فتح الملف المحدد"
            override val openSettings = "فتح الإعدادات"
            override val recordAction = "تسجيل"
            override val recordingHint = "اضغط على الاختصار الجديد…"
            override val cancelAction = "إلغاء"
            override val clearAction = "إلغاء الربط"
            override val resetAction = "إعادة تعيين"
            override val restoreAllAction = "استعادة كل الافتراضيات"
            override val unboundLabel = "غير مربوط"
            override fun conflictError(otherAction: String) = "مستخدم بالفعل بواسطة «$otherAction»"
            override val needModifierError = "أضف Ctrl/⌘ أو Alt، أو اختر مفتاحًا لا يكتب نصًا"
        }
    }

    override val errors = object : BlazeStrings.Errors {
        override val networkUnavailable = "الشبكة غير متاحة"
        override val timeout = "انتهت مهلة الاتصال"
        override val unauthorized = "وصول غير مصرح به"
        override val notFound = "الملف غير موجود"
        override val diskFull = "القرص ممتلئ"
        override val rangeUnsupported = "الاستئناف غير مدعوم"
        override val invalidTorrent = "ملف torrent غير صالح"
        override val metadataTimeout = "فشل جلب البيانات الوصفية"
        override val cancelled = "أُلغي التنزيل"
        override fun networkFailure(message: String) = "عطل في الشبكة: $message"
        override fun diskError(message: String) = "خطأ في القرص: $message"
        override fun unknown(message: String) = "خطأ غير معروف: $message"
    }

    override val consent = object : BlazeStrings.Consent {
        override val clientTitle = "تطبيق خارجي يريد التحكم في Blaze"
        override fun clientBody(name: String, id: String) =
            "اتصل «$name» بواجهة التنزيل المحلية في Blaze وقدّم رمز وصول صالحًا."
        override val clientMeaning =
            "إذا سمحت له، يستطيع هذا البرنامج إضافة التنزيلات وإيقافها مؤقتًا واستئنافها وإعادة تشغيلها وحذفها على هذا الجهاز ومراقبة تقدّمها؛ لا تسمح إلا للتطبيقات التي تتوقع اتصالها وتثق بها."
        override val extensionTitle = "امتداد غير معتمد يريد التنفيذ"
        override fun extensionBody(fileName: String) =
            "«$fileName» امتداد طرف ثالث لـ Blaze."
        override val extensionMeaning =
            "الامتدادات ليست بيانات خاملة: شيفراتها تعمل داخل Blaze بنفس صلاحية التطبيق على ملفاتك وشبكتك، وقد يمتد ضرر امتداد خبيث إلى جهازك فعليًا؛ لا تسمح إلا بملفات jar من مصادر تثق بها."
        override val choiceHelp =
            "«السماح هذه المرة فقط» يوافق على هذا الطلب ويسأل مجددًا بعد إعادة تشغيل Blaze؛ أما «السماح دائمًا» فيحفظ الموافقة فلا يُعاد السؤال — ويمكنك التراجع عنها في الإعدادات ← عام ← الأمان."
        override val settingsHint = "يمكنك إيقاف هذه الرسائل ومراجعة كل ما سمحت به من الإعدادات ← عام ← الأمان."
        override val allowOnce = "السماح هذه المرة فقط"
        override val allowAlways = "السماح دائمًا"
        override val deny = "الرفض"
        override val subjectLabel = "المُعرِّف:"
    }

    override val options = object : BlazeStrings.Options {
        override val integerRequired = "أدخل عددًا صحيحًا"
        override fun integerRange(min: Long, max: Long) = "يجب أن يكون بين \u0024min و\u0024max"
        override val colorInvalid = "أدخل لونًا سداسيًا صالحًا"
    }
}
