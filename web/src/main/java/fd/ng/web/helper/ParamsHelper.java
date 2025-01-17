package fd.ng.web.helper;

import fd.ng.core.bean.FeedBean;
import fd.ng.core.exception.BusinessProcessException;
import fd.ng.core.exception.internal.FrameworkRuntimeException;
import fd.ng.core.utils.*;
import fd.ng.web.annotation.*;
import fd.ng.web.conf.WebinfoConf;
import fd.ng.web.fileupload.FileItem;
import fd.ng.web.fileupload.disk.DiskFileItemFactory;
import fd.ng.web.fileupload.servlet.ServletFileUpload;
import fd.ng.web.util.FileUploadUtil;
import fd.ng.web.util.RequestUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

public class ParamsHelper {
	private static final Logger logger = LogManager.getLogger(ParamsHelper.class.getName());
	private static final Object[] EMPTY_ARGS = new Object[]{}; // 方法无参数时，赋予invode调用的值

	public static Object[] autowireParameters(final HttpServletRequest request, final Method actionMethod) {
//		long start = 0;
//		if(logger.isDebugEnabled()) start = System.currentTimeMillis();

		Map<String, String[]> parameterMap;
		UploadFile uploadFileAnno = actionMethod.getAnnotation(UploadFile.class);
		if(uploadFileAnno==null)
			parameterMap = request.getParameterMap();
		else
			parameterMap = genParameterMapForFileUpload(request, uploadFileAnno);
		//Class<?>[] paramTypes = actionMethod.getParameterTypes(); // 每个参数对象的类型
		Parameter[] parameters = actionMethod.getParameters();
		if(parameters.length==0) return EMPTY_ARGS; // 该方法无参数

		Object[] paramsValueObject = new Object[parameters.length]; // 用于存储每个参数的值
		//Arrays.fill(paramsValueObject, null);
		StringJoiner sj = new StringJoiner(", "); // 记录不合法的参数
		int paramSize = parameters.length;
		for(int i=0; i<paramSize; i++) {
			Parameter param = parameters[i]; // 方法的参数对象
			Class<?> paramType = param.getType(); // 方法的参数类型
			/** 【1】 处理 HttpServletRequest 和 Bean 类型的参数 */
			if(HttpServletRequest.class.isAssignableFrom(paramType)) { // 当前参数是HttpServletRequest
				paramsValueObject[i] = request;
				continue;
			}
			if(FeedBean.class.isAssignableFrom(paramType)) { // 当前参数是FeedBean
				paramsValueObject[i] = RequestUtil.buildBeanFromRequest(request, paramType);
				continue;
			}
			if(param.isAnnotationPresent(RequestBean.class)) { // 该参数定义了RequestBean注解，意味着是一个Bean
				paramsValueObject[i] = RequestUtil.buildBeanFromRequest(request, paramType);
				continue;
			}
			String reqParamName = param.getName(); // 该参数在 request 中的名字。默认就是参数名
			boolean required = true;
			String[] defaultValue = null;
			/** 【2】 根据当前参数的注解，设置参数名、是否可空、默认值 */
			RequestParam annoRequestParam = param.getAnnotation(RequestParam.class);
			if(annoRequestParam!=null) { // 该参数定义了RequestParam注解，意味着是一个主类型
				if(annoRequestParam.ignore()) continue;
				String aliasName = annoRequestParam.name();
				if(StringUtil.isNotEmpty(aliasName)) reqParamName = aliasName;
				required = !annoRequestParam.nullable();
				String[] defaultValue0 = annoRequestParam.valueIfNull();
				if(ArrayUtil.isNotEmpty(defaultValue0)) {
					defaultValue = defaultValue0;
					required = false; // 设置了默认值，那么就意味着该参数可以为空（agreeNull注解的值成为无效设置）
				}
			}
			String[] paramValueArr = getOrNull(parameterMap, reqParamName);  // 从 request 中取值
			if(required) { // 值不允许null。即：没有设置注解、 agreeNull 设置为false、没有设置明确设置 agreeNull
				if(paramValueArr==null) {
					sj.add(reqParamName); // 从request中取值为null，则记录下这个错误的参数名
					continue;
				}
			} else { // 允许空值
				if(paramValueArr==null&&defaultValue!=null) { // request 中取值为null，并且注解中赋予了默认值
					paramValueArr = defaultValue;
				}
			}
			/** 【3】 为方法参数赋值 */
			try {
				paramsValueObject[i] = BeanUtil.castStringToClass(paramValueArr, paramType);
			} catch (BeanUtil.ArgumentNullvalueException anvex) {
				if(logger.isDebugEnabled())
					logger.debug(String.format("argument:%s(%s) must not be null!", param.getName(), paramType.getSimpleName()));
				sj.add(reqParamName); // 为当前参数构造值，出现了错误，则记录下这个产生错误的参数名
			} catch (BeanUtil.ArgumentUnsupportedTypeException autex) {
				if(logger.isDebugEnabled())
					logger.debug(String.format("argument:%s 's type:%s is Unsupported!", param.getName(), paramType.getSimpleName()));
				sj.add(reqParamName);
			}
		}

//		if(logger.isDebugEnabled()) {
//			logger.debug("  [bizid:{}] autowire parameters processing time : {}", Thread.currentThread().getId(),
//					(System.currentTimeMillis() - start));
//		}
		if(sj.length()>0) {
			logger.error(String.format("URL=[%s], ActionMethod=[%s] 处理失败。%n发生错误的参数： %s%n发生错误的原因：%n  %s%n  %s%n  %s"
					,request.getPathInfo(), actionMethod.getName(), sj.toString()
					,"1）前端没有提交这些名字的数据"
					,"2）这些名字如果定义在方法参数的注解中，请检查注解的name属性赋值是否正确"
					,"3）参数是 JavaBean 类型，但是没有使用 RequestBean 注解"));
			throw new BusinessProcessException(
					String.format("error request parameters : (%s)", sj.toString()));
		}
		return paramsValueObject;
	}

	public static Object[] assignParameters(final Method actionMethod, final HttpServletRequest request) {
		//testPerfRequestGet(request);
		long start = 0;
		if(logger.isDebugEnabled()) start = System.currentTimeMillis();
		Object[] args = assignParameters(actionMethod, request.getParameterMap(), request);
		if(logger.isDebugEnabled()) {
			logger.debug("  [bizid:{}] assignParameters Processing time : {}", Thread.currentThread().getId(),
					(System.currentTimeMillis() - start));
		}
		return args;
	}

	/**
	 *
	 * @param actionMethod
	 * @param parameterMap
	 * @param request HttpServletRequest 传入这个参数，仅仅是为了对这种参数的方法，方便返回HttpServletRequest对象而已。
	 * @return
	 */
	public static Object[] assignParameters(final Method actionMethod, final Map<String, String[]> parameterMap, final HttpServletRequest request) {
		Class<?>[] methodParamTypes = actionMethod.getParameterTypes(); // 每个参数对象的类型
		if(methodParamTypes.length==0) return EMPTY_ARGS; // 该方法无参数
		else if(methodParamTypes.length==1&&methodParamTypes[0].isAssignableFrom(HttpServletRequest.class))
			return new Object[]{request}; // 该方法仅有一个HttpServletRequest参数
		// 注解形如： @Params("String username, int age, String sex:null, String[] addr")
		Params params = actionMethod.getAnnotation(Params.class); // 得到该方法的 Params 注解
		if(params==null) { // 有参数且不是request，那么必须定义注解
//			if(methodParamTypes.length!=1 || !(methodParamTypes[0].isAssignableFrom(HttpServletRequest.class)) )
				throw new BusinessProcessException(
						String.format("Action方法：%s(...) 定义不合法： " +
										"必须通过Params注解定义参数，或者定义为一个 HttpServletRequest 类型的参数。"
								, actionMethod.getName()));
		}
		List<String> annoParamArr = StringUtil.split(params.value(), ","); // 注解中的每个参数定义
		if(logger.isTraceEnabled()) {
			if(annoParamArr.size()!=methodParamTypes.length) {
				logger.trace(
						String.format("%s 方法的注解中配置的参数个数(=%d)，与方法的参数个数(=%d)不一致",
						actionMethod.getName(), annoParamArr.size(), methodParamTypes.length));
			}
		}

		Object[] paramsValueObject = new Object[methodParamTypes.length]; // 用于存储每个参数的值
		Arrays.fill(paramsValueObject, null);
		int loops = annoParamArr.size();
		if(methodParamTypes.length<loops) loops = methodParamTypes.length; // 方法参数个数比注解中的少
		for(int i=0; i<loops; i++) {
			if(HttpServletRequest.class.isAssignableFrom(methodParamTypes[i])) { // 当前参数是HttpServletRequest
				paramsValueObject[i] = request;
				continue;
//			} else if(EntityBean.class.isAssignableFrom(methodParamTypes[i])) {
//				paramsValueObject[i] = RequestUtil.buildBeanFromRequest(request, methodParamTypes[i]);
//				continue;
			}
			if(methodParamTypes[i].getAnnotation(RequestBean.class)!=null) {
					paramsValueObject[i] = RequestUtil.buildBeanFromRequest(request, methodParamTypes[i]);
					continue;
			}
			/** 【1】 首先把每个注解参数，用空格进行分割
			 * 因为注解里的每个参数是用空格分隔的（也允许没有空格）
			 * 形如： "String username, int age, String sex:null, String[] addr"
			 */
			List<String> oneAnnoParam = StringUtil.split(annoParamArr.get(i).trim(), " ");
			String paramName; // 例如： name:null
			if(oneAnnoParam.size()==1) paramName = oneAnnoParam.get(0); // 没有空格分隔的情况，如：@Params("name, favors")
			else if(oneAnnoParam.size()==2) paramName = oneAnnoParam.get(1);
			else {
				throw new BusinessProcessException(
						String.format("Action方法：%s(...)的Params注解里面的参数:[%s]不合法（被分割为%d个）。正确格式例如： String name 或 String name:null 或 name",
								actionMethod.getName(), annoParamArr.get(i), oneAnnoParam.size()));
			}
			/** 【2】 判断是否[ :null ]结尾，用于处理是否允许被设置成 null。 */
			int canBeNull = paramName.indexOf(":null");
			if(canBeNull>0) paramName = paramName.substring(0, canBeNull);
			String[] paramValueArr = parameterMap.get(paramName);
			if(canBeNull<0) { // 注解中不允许null
				if(paramValueArr==null || paramValueArr.length==0
						|| ( paramValueArr.length==1&&StringUtil.isEmpty(paramValueArr[0]) ) ) { // 不是数组且为空
					// 从request中取值为null
					logger.error(String.format("Action方法：%s(...) 的第[%d]个参数不允许为空，" +
									"但是，request中，这个参数取值为 null。原因：" +
									"1）方法的第[%d]个参数在注解定义的名字(%s)写错了；2）前端没有提交数据；" +
									"3）如果这个参数是 JavaBean 类型，那么必须定义 RequestBean 注解。"
							, actionMethod.getName(), (i+1), (i+1), paramName));
					throw new BusinessProcessException(
							String.format("error request parameters : (%s)", paramName));
				}
			} else { // 注解中允许空
				if(paramValueArr==null || paramValueArr.length==0
						|| ( paramValueArr.length==1&&StringUtil.isEmpty(paramValueArr[0]) ) ) { // 不是数组且为空
					// 从request中取值为null，则不需要做赋值处理了。因为已经初始化为null了
					continue;
				}
			}
			/** 【3】 为方法参数赋值 */
			try {
				paramsValueObject[i] = BeanUtil.castStringToClass(paramValueArr, methodParamTypes[i]);
			} catch (BeanUtil.ArgumentNullvalueException anvex) {
				throw new FrameworkRuntimeException(String.format("argument:%s(%s) must not be null!", paramName, methodParamTypes[i].getSimpleName()));
			} catch (BeanUtil.ArgumentUnsupportedTypeException autex) {
				throw new FrameworkRuntimeException(String.format("argument:%s 's type:%s is Unsupported!", paramName, methodParamTypes[i].getSimpleName()));
			}
		}
		return paramsValueObject;
	}

	private static Map<String, String[]> genParameterMapForFileUpload(HttpServletRequest request, UploadFile uploadFileAnno) {
		DiskFileItemFactory factory = new DiskFileItemFactory();

		// Max Memory Size 设置是否使用临时文件保存解析出的数据的临界值（单位是字节）
		// 对于上传的字段内容，需要临时保存解析出的数据。因为内存有限，所以对于太大的数据要用临时文件来保存这些数据
		// FileItem类对象内部用了两个成员变量来分别存储提交上来的数据的描述头和主体内容
		// 当主体内容的大小小于setSizeThreshold方法设置的临界值时，主体内容将会被保存在内存中
		factory.setSizeThreshold(WebinfoConf.FileUpload_SizeThreshold);
		// setSizeThreshold的配套方法，用于设置setSizeThreshold方法中提到的临时文件的存放目录（必须使用绝对路径）
		// 如果不设置，则使用 java.io.tmpdir 环境属性所指定的目录
		// 临时文件命名规则： upload_00000005（八位或八位以上的数字）.tmp
		factory.setRepository(WebinfoConf.FileUpload_RepositoryDir);
		factory.setDefaultCharset(CodecUtil.UTF8_STRING);

		// Create a new file upload handler
		ServletFileUpload upload = new ServletFileUpload(factory);

		upload.setHeaderEncoding(CodecUtil.UTF8_STRING); // 解决中文路径或文件名乱码？
		upload.setSizeMax(WebinfoConf.FileUpload_FilesTotalSize); // Max Request Size, 上传文件允许的总大小。如果一次上传多个文件，是所有文件大小之和的上限

		// key：每个form元素的name， value：form元素的值。如果是重名元素，都会放到 List 中。
		// 对于上传的文件，value中保存了新文件全路径、原始文件名、文件大小、文件类型，使用FileUploadUtil.FILEINFO_SEPARATOR分隔：
		Map<String, String[]> paramMap = new HashMap<>();
		String fieldName=null; // 这里定义是为了当出现异常时，记录处理到哪个元素了
		try {
			String savedDir = uploadFileAnno.savedDir();
			if(StringUtil.isBlank(savedDir)) savedDir = WebinfoConf.FileUpload_SavedDirName;
			else savedDir = FileUtil.fixPathName(savedDir);

			List<FileItem> fileItems = upload.parseRequest(request);
			Map<String, Integer> paramCount = new HashMap<>(); // 记录每个form元素的个数
			for(FileItem item : fileItems) {
				int count = paramCount.getOrDefault(item.getFieldName(), 0);
				paramCount.put(item.getFieldName(), count+1);
			}
			Map<String, Integer> paramIndex = new HashMap<>(); // 记录每个元素处理的索引。
			for(FileItem item : fileItems) {
				fieldName = item.getFieldName(); // form元素的name
				final int curItemIndex = paramIndex.getOrDefault(fieldName, 0);
				paramIndex.put(fieldName, curItemIndex+1); // 下一次的数组位置
				final int sameNameItemCount = paramCount.get(fieldName); // 当前名字的item有几个
				if (item.isFormField()) {
					String[] paramValues = paramMap.computeIfAbsent(fieldName, k->new String[sameNameItemCount]);
					String valueUTF8 = item.getString(CodecUtil.UTF8_STRING);
					paramValues[curItemIndex]=(valueUTF8);
				} else { // 处理上传的文件
					// 注意：Windows系统上传文件，浏览器将传递该文件的完整路径，Linux系统上传文件，浏览器只传递该文件的名称
					String orgnFileName = item.getName(); // 原始文件名
					// 剔除掉路径
					// TODO 某些情况下，路径符号、中文等会被urlencode，下面的方式就无法正确剥离出原始文件名
					int loc = orgnFileName.lastIndexOf(FileUtil.SHIT_PATH_SEPARATOR);
					if(loc>-1) orgnFileName = orgnFileName.substring(loc+1);
					else {
						loc = orgnFileName.lastIndexOf(FileUtil.LINUX_PATH_SEPARATOR);
						if(loc>-1) orgnFileName = orgnFileName.substring(loc+1);
					}
					// 新的文件名（全路径名）
					String newFilename = savedDir + UuidUtil.uuid();
					loc = orgnFileName.lastIndexOf(FileUtil.FILE_EXT);
					if(loc>0) newFilename += orgnFileName.substring(loc);
					item.write(new File(newFilename)); // 用于将FileItem对象中保存的主体内容保存到某个指定的文件中
//					InputStream uploadedStream = item.getInputStream(); // 直接对文件二进制内容流进行操作
//					uploadedStream.close();
//					byte[] data = item.get(); // 直接得到内存中的文件
					item.delete(); // 清空FileItem类对象中存放的主体内容，如果主体内容被保存在临时文件中，delete方法将删除该临时文件

					String[] fileItemValues = paramMap.computeIfAbsent(fieldName, k->new String[sameNameItemCount]);
					fileItemValues[curItemIndex] = newFilename + FileUploadUtil.FILEINFO_SEPARATOR + orgnFileName
							+ FileUploadUtil.FILEINFO_SEPARATOR + item.getSize() + FileUploadUtil.FILEINFO_SEPARATOR + item.getContentType();
				}
			}
			return paramMap;
		} catch (Exception e) {
			throw new FrameworkRuntimeException("Current dealed fieldName : " + fieldName, e);
		}
	}

	/**
	 * 根据 name 从 request 中取值。
	 * 只有取到实际存在的数据，才返回取到的数据，否则返回 null ，也就是说，如果空值数组也返回 null
	 * 可用于对request取值后的空值判断。
	 * @param request HttpServletRequest
	 * @param name String 从request里取值的name
	 * @return String[] 取到的实际值，或 null。
	 */
	public static String[] getOrNull(final HttpServletRequest request, final String name) {
		return getOrNull(request.getParameterMap(), name);
	}
	public static String[] getOrNull(final Map<String, String[]> requestParameterMap, final String name) {
		String[] valueArr = requestParameterMap.get(name);
		if (valueArr == null || valueArr.length == 0) return null;
		else if (valueArr.length == 1 && StringUtil.isEmpty(valueArr[0])) return null;
		else return valueArr;
	}

	/**
	 * 测试 getParameterMap getParameterValues getParameter 三个方法的处理性能。
	 * 结果：性能没有区别
	 * @param request
	 */
	private void testPerfRequestGet(HttpServletRequest request) {
		// 测试 getParameterMap 性能
		long start = System.currentTimeMillis();
		Map<String, String[]> parameterMap = request.getParameterMap();
		String[] names = parameterMap.get("name");
		String name;
		if(names!=null&&names.length==1) name = names[0];
		else name = null;
		System.out.println("getParameterMap 一次 time : " + (System.currentTimeMillis() - start));
		start = System.currentTimeMillis();
		for (int i = 0; i < 10000; i++) {
			parameterMap = request.getParameterMap();
			names = parameterMap.get("name");
			if(names!=null&&names.length==1) name = names[0];
			else name = null;
		}
		System.out.println("getParameterMap 万次 time : " + (System.currentTimeMillis() - start));

		// 测试 get 性能
		start = System.currentTimeMillis();
		name = request.getParameter("name");
		System.out.println("getParameter    一次 time : " + (System.currentTimeMillis() - start));
		start = System.currentTimeMillis();
		for (int i = 0; i < 10000; i++) {
			names = request.getParameterValues("name");
			if(names==null) name = request.getParameter("name");
			else name = names[0];
		}
		System.out.println("getParameter    万次 time : " + (System.currentTimeMillis() - start));
	}
}
