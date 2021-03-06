/*Copyright ©2016 TommyLemon(https://github.com/TommyLemon/APIJSON)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.*/

package zuo.biao.apijson.server;

import static zuo.biao.apijson.RequestMethod.GET;
import static zuo.biao.apijson.RequestMethod.HEAD;
import static zuo.biao.apijson.RequestMethod.POST;
import static zuo.biao.apijson.RequestMethod.POST_GET;
import static zuo.biao.apijson.RequestMethod.POST_HEAD;
import static zuo.biao.apijson.RequestMethod.PUT;
import static zuo.biao.apijson.StringUtil.UTF_8;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import zuo.biao.apijson.JSON;
import zuo.biao.apijson.JSONResponse;
import zuo.biao.apijson.Log;
import zuo.biao.apijson.RequestMethod;
import zuo.biao.apijson.StringUtil;
import zuo.biao.apijson.server.exception.ConditionNotMatchException;
import zuo.biao.apijson.server.exception.ConflictException;
import zuo.biao.apijson.server.exception.NotExistException;
import zuo.biao.apijson.server.sql.AccessVerifier;
import zuo.biao.apijson.server.sql.QueryConfig;
import zuo.biao.apijson.server.sql.QueryHelper;

/**parser for parsing request to JSONObject
 * @author Lemon
 */
public class Parser {
	private static final String TAG = "Parser";

	public static final String SEPARATOR = StringUtil.SEPARATOR;

	private RequestMethod requestMethod;
	/**
	 * GET
	 */
	public Parser() {
		this(null);
	}
	/**
	 * @param requestMethod null ? requestMethod = GET
	 */
	public Parser(RequestMethod requestMethod) {
		super();
		if (requestMethod == null) {
			requestMethod = GET;
		}
		this.requestMethod = requestMethod;
	}


	private JSONObject requestObject;
	private QueryHelper queryHelper;
	private Map<String, Object> queryResultMap;//path-result


	/**解析请求json并获取对应结果
	 * @param request
	 * @return
	 */
	public String parse(String request) {
		String response = JSON.toJSONString(parseResponse(request));

		Log.d(TAG, "parse  return response = \n" + response
				+ "\n >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>> \n\n\n");

		return response;
	}
	/**解析请求json并获取对应结果
	 * @param request
	 * @return
	 */
	public String parse(JSONObject request) {
		return JSON.toJSONString(parseResponse(request));
	}

	/**解析请求json并获取对应结果
	 * @param request 先parseRequest中URLDecoder.decode(request, UTF_8);再parseResponse(getCorrectRequest(...))
	 * @return parseResponse(requestObject);
	 */
	public JSONObject parseResponse(String request) {
		Log.d(TAG, "\n\n\n\n<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<\n"
				+ requestMethod + "/parseResponse  request = \n" + request + "\n\n");
		try {
			requestObject = getCorrectRequest(requestMethod, parseRequest(request, requestMethod));
		} catch (Exception e) {
			return newErrorResult(e);
		}
		return parseResponse(requestObject);
	}
	/**解析请求json并获取对应结果
	 * @param request
	 * @return requestObject
	 */
	public JSONObject parseResponse(JSONObject request) {
		final String requestString = JSON.toJSONString(request);//request传进去解析后已经变了

		queryResultMap = new HashMap<String, Object>();

		Exception error = null;
		queryHelper = new QueryHelper();
		try {
			requestObject = getObject(null, null, request);
		} catch (Exception e) {
			e.printStackTrace();
			error = e;
		}
		queryHelper.close();
		queryHelper = null;


		requestObject = AccessVerifier.removeAccessInfo(requestObject);
		requestObject = error == null ? extendSuccessResult(requestObject)
				: extendResult(requestObject, 206, "未完成全部请求：\n" + error.getMessage());


		queryResultMap.clear();

		Log.d(TAG, "\n\n\n\n<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<\n "
				+ requestMethod + "/parseResponse  request = \n" + requestString + "\n\n");

		return requestObject;
	}

	/**解析请求JSONObject
	 * @param request => URLDecoder.decode(request, UTF_8);
	 * @return
	 */
	public static JSONObject parseRequest(String request, RequestMethod method) {
		try {
			request = URLDecoder.decode(request, UTF_8);
		} catch (UnsupportedEncodingException e) {
			return newErrorResult(e);
		}
		if (method == null) {
			method = GET;
		}
		Log.d(TAG, "\n\n\n\n<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<\n " + method
				+ "/parseResponse  request = \n" + request + "\n\n");
		return JSON.parseObject(request);
	}


	/**是否为GET请求方法
	 * @param method
	 * @param containPrivate 包含私密(非明文)获取方法POST_GET
	 * @return
	 */
	public static boolean isGetMethod(RequestMethod method, boolean containPrivate) {
		boolean is = method == null || method == GET;
		return containPrivate == false ? is : is || method == POST_GET;
	}
	/**是否为HEAD请求方法
	 * @param method
	 * @param containPrivate 包含私密(非明文)获取方法POST_HEAD
	 * @return
	 */
	public static boolean isHeadMethod(RequestMethod method, boolean containPrivate) {
		boolean is = method == HEAD;
		return containPrivate == false ? is : is || method == POST_HEAD;
	}
	/**是否为公开(明文，浏览器能直接访问)的请求方法
	 * @param method
	 * @return
	 */
	public static boolean isPublicMethod(RequestMethod method) {
		return method == null || method == GET || method == HEAD;
	}

	/**新建带状态内容的JSONObject
	 * @param status
	 * @param message
	 * @return
	 */
	public static JSONObject newResult(int status, String message) {
		return extendResult(null, status, message);
	}
	/**添加JSONObject的状态内容，一般用于错误提示结果
	 * @param object
	 * @param status
	 * @param message
	 * @return
	 */
	public static JSONObject extendResult(JSONObject object, int status, String message) {
		if (object == null) {
			object = new JSONObject(true);
		}
		object.put(JSONResponse.KEY_STATUS, status);
		object.put(JSONResponse.KEY_MESSAGE, message);
		return object;
	}


	/**添加请求成功的状态内容
	 * @param object
	 * @return
	 */
	public static JSONObject extendSuccessResult(JSONObject object) {
		return extendResult(object, 200, "success");
	}
	/**获取请求成功的状态内容
	 * @return
	 */
	public static JSONObject newSuccessResult() {
		return newResult(200, "success");
	}
	/**添加请求成功的状态内容
	 * @param object
	 * @return
	 */
	public static JSONObject extendErrorResult(JSONObject object, Exception e) {
		JSONObject error = newErrorResult(e);
		return extendResult(object, error.getIntValue(JSONResponse.KEY_STATUS), error.getString(JSONResponse.KEY_MESSAGE));
	}
	/**新建错误状态内容
	 * @param e
	 * @return
	 */
	public static JSONObject newErrorResult(Exception e) {
		if (e != null) {
			e.printStackTrace();

			int status = 404;
			if (e instanceof UnsupportedEncodingException) {
				status = 400;
			} else if (e instanceof IllegalAccessException) {
				status = 401;
			} else if (e instanceof UnsupportedOperationException) {
				status = 403;
			} else if (e instanceof IllegalArgumentException) {
				status = 406;
			} else if (e instanceof TimeoutException) {
				status = 408;
			} else if (e instanceof ConflictException) {
				status = 409;
			} else if (e instanceof ConditionNotMatchException) {
				status = 412;
			}

			return newResult(status, e.getMessage());
		}
		return newResult(500, "服务器内部错误");
	}






	/**获取正确的请求，非GET请求必须是服务器指定的
	 * @param method
	 * @param request
	 * @return
	 * @throws Exception 
	 */
	public static JSONObject getCorrectRequest(RequestMethod method, JSONObject request) throws Exception {
		return getCorrectRequest(method, request, null);
	}
	/**获取正确的请求，非GET请求必须是服务器指定的
	 * @param method
	 * @param request
	 * @param queryHelper
	 * @return
	 */
	public static JSONObject getCorrectRequest(RequestMethod method, JSONObject request, QueryHelper queryHelper)
			throws Exception {
		if (isPublicMethod(method)) {
			return request;//需要指定JSON结构的get请求可以改为post请求。一般只有对安全性要求高的才会指定，而这种情况用明文的GET方式几乎肯定不安全
		}

		String tag = request.getString(JSONRequest.KEY_TAG);
		if (StringUtil.isNotEmpty(tag, true) == false) {
			throw new IllegalArgumentException("请指定tag！一般是table名称");
		}

		//获取指定的JSON结构 <<<<<<<<<<<<<<
		QueryConfig config = new QueryConfig(GET, "Request");
		config.setColumn("structure");

		Map<String, Object> where = new HashMap<String, Object>();
		where.put("method", method.name());
		where.put(JSONRequest.KEY_TAG, tag);
		config.setWhere(where);

		JSONObject object = null;
		String error = "";
		if (queryHelper == null) {
			queryHelper = new QueryHelper();
		}
		try {
			object = queryHelper.select(config);
		} catch (Exception e) {
			e.printStackTrace();
			error = e.getMessage();
		}
		queryHelper.close();

		if (object == null || object.isEmpty()) {
			throw new UnsupportedOperationException("非GET请求必须是服务端允许的操作！ \n " + error);
		}
		object = getJSONObject(object, "structure");//解决返回值套了一层 "structure":{}

		JSONObject target = null;
		if (isTableKey(tag) && object.containsKey(tag) == false) {//tag是table名
			target = new JSONObject(true);
			target.put(tag, object);
		} else {
			target = object;
		}
		//获取指定的JSON结构 >>>>>>>>>>>>>>

		request.remove(JSONRequest.KEY_TAG);
		return fillTarget(method, target, request, "");
	}


	public static final String NECESSARY_COLUMNS = "necessaryColumns";
	public static final String DISALLOW_COLUMNS = "disallowColumns";

	/**从request提取target指定的内容
	 * @param target
	 * @param request
	 * @return
	 */
	public static JSONObject fillTarget(RequestMethod method
			, JSONObject target, final JSONObject request, String requestName) throws Exception {
		Log.i(TAG, "filterTarget  requestName = " + requestName
				+ " target = \n" + JSON.toJSONString(target)
				+ "\n request = \n" + JSON.toJSONString(request) + "\n >> return null;");
		if (target == null || request == null) {// || request.isEmpty()) {
			Log.i(TAG, "filterTarget  target == null || request == null >> return null;");
			return null;
		}

		/**方法三：遍历request，transferredRequest只添加target所包含的object
		 *  ，且移除target中DISALLOW_COLUMNS，期间判断NECESSARY_COLUMNS是否都有
		 */
		String necessarys = StringUtil.getNoBlankString(target.getString(NECESSARY_COLUMNS));
		String[] necessaryColumns = StringUtil.split(necessarys);

		//判断必要字段是否都有
		if (necessaryColumns != null) {
			for (String s : necessaryColumns) {
				if (request.containsKey(s) == false) {
					throw new IllegalArgumentException(requestName
							+ "不能缺少 " + s + " 等[" + necessarys + "]内的任何字段！");
				}
			}
		}

		String disallows = StringUtil.getNoBlankString(target.getString(DISALLOW_COLUMNS));
		String[] disallowColumns = null;

		Set<String> set = request.keySet();
		if ("!".equals(disallows)) {//所有非necessaryColumns，改成 !necessary 更好
			if (set != null) {
				List<String> disallowList = new ArrayList<String>();
				for (String key : set) {
					if (isContainKeyInArray(key, necessaryColumns) == false) {
						disallowList.add(key);
					}
				}
				disallowColumns = disallowList.toArray(new String[]{});
			}
		} else {
			disallowColumns = StringUtil.split(disallows);
		}


		//填充target
		JSONObject transferredRequest = new JSONObject(true);
		if (set != null) {
			Object value;
			JSONObject result;
			for (String key : set) {
				value = request.get(key);
				if (value instanceof JSONObject) {//JSONObject，往下一级提取
					if (target.containsKey(key)) {//只填充target有的object
						result = fillTarget(method, getJSONObject(target, key), (JSONObject) value, key);//往下一级提取
						Log.i(TAG, "fillTarget  key = " + key + "; result = " + result);
						if (result == null || result.isEmpty()) {//只添加!=null的值，可能数据库返回数据不够count
							throw new IllegalArgumentException(requestName
									+ "不能缺少 " + key + " 等[" + necessarys + "]内的任何JSONObject！");
						}
						if (method == POST && result.containsKey(QueryConfig.ID) == false) {//为注册用户返回id
							result.put(QueryConfig.ID, System.currentTimeMillis());
						}
						transferredRequest.put(key, result);
					}
				} else {//JSONArray或其它Object
					if (isContainKeyInArray(key, disallowColumns)) {
						throw new IllegalArgumentException(requestName
								+ "不允许传 " + key + " 等[" + disallows + "]内的任何字段！");
					}
					transferredRequest.put(key, value);
				}
			}
		}

		Log.i(TAG, "filterTarget  return transferredRequest = " + JSON.toJSONString(transferredRequest));
		return transferredRequest;
	}

	/**array至少有一个值在request的key中
	 * @param key
	 * @param array
	 * @return
	 */
	public static boolean isContainKeyInArray(String key, String[] array) {
		if (array == null || array.length <= 0 || key == null) {
			Log.i(TAG, "isContainKeyInArray"
					+ "  array == null || array.length <= 0 || key == null >> return false;");
			return false;
		}

		for (String s : array) {
			if (key.equals(s)) {
				return true;
			}
		}

		return false;
	}





	/**获取单个对象，该对象处于parentObject内
	 * @param parentPath parentObject的路径
	 * @param parentConfig 对子object的SQL查询配置，需要传两个层级
	 * @param name parentObject的key
	 * @param request parentObject的value
	 * @return
	 * @throws Exception 
	 */
	private JSONObject getObject(String parentPath, String name
			, final JSONObject request) throws Exception {
		Log.i(TAG, "\ngetObject:  parentPath = " + parentPath
				+ ";\n name = " + name + "; request = " + JSON.toJSONString(request));
		if (request == null) {// Moment:{} || request.isEmpty()) {//key-value条件
			return null;
		}
		final String path = getAbsPath(parentPath, name);

		//为第二遍parseRelation = true服务，优化查询性能并避免"[]":{"0":{"1":{}}}这种导致第3层当成[]的直接子Object
		final String table = Pair.parseEntry(name, true).getKey();
		final boolean isTableKey = isTableKey(table);
		Log.d(TAG, "getObject  table = " + table + "; isTableKey = " + isTableKey);



		JSONObject transferredRequest = new JSONObject(true);//must init
		Map<String, String> functionMap = null;
		Map<String, Object> selfDefineKeyMap = null;

		Set<String> set = new LinkedHashSet<>(request.keySet());
		if (set != null && set.size() > 0) {//判断换取少几个变量的初始化是否值得？
			functionMap = new LinkedHashMap<String, String>();
			selfDefineKeyMap = new LinkedHashMap<String, Object>();

			Object value;
			com.alibaba.fastjson.JSON result;
			boolean isEmpty = false;
			for (String key : set) {
				value = request.get(key);
				if (value == null) {
					continue;
				}

				if (value instanceof JSONObject) {//JSONObject，往下一级提取
					if (isArrayKey(key)) {//APIJSON Array
						result = getArray(path, key, (JSONObject) value);
						isEmpty = result == null || ((JSONArray) result).isEmpty();
					} else {//APIJSON Object
						result = getObject(path, key, (JSONObject) value);
						isEmpty = result == null || ((JSONObject) result).isEmpty();
					}
					Log.i(TAG, "getObject  key = " + key + "; result = " + result);
					if (isEmpty == false) {//只添加!=null的值，可能数据库返回数据不够count
						transferredRequest.put(key, result);
					}
				} else if (requestMethod == PUT && JSON.isJSONArray(value)) {//PUT JSONArray
					JSONArray array = ((JSONArray) value);
					if (array != null && array.isEmpty() == false && isTableKey(table)) {
						int putType = 0;
						if (key.endsWith("+")) {//add
							putType = 1;
						} else if (key.endsWith("-")) {//remove
							putType = 2;
						} else {//replace
							throw new IllegalAccessException("PUT " + path + ", PUT Array不允许 " + key + 
									" 这种没有 + 或 - 结尾的key！不允许整个替换掉原来的Array！");
						}
						String realKey = getRealKey(requestMethod, key, false, false);

						//GET > add all 或 remove all > PUT > remove key

						//GET <<<<<<<<<<<<<<<<<<<<<<<<<
						JSONObject arrayRequest = new JSONObject();
						arrayRequest.put(QueryConfig.ID, request.get(QueryConfig.ID));
						arrayRequest.put(JSONRequest.KEY_COLUMN, realKey);
						JSONRequest getRequest = new JSONRequest(table, arrayRequest);
						JSONObject response = new Parser().parseResponse(getRequest);
						//GET >>>>>>>>>>>>>>>>>>>>>>>>>


						//add all 或 remove all <<<<<<<<<<<<<<<<<<<<<<<<<
						response = response == null ? null : response.getJSONObject(table);
						JSONArray targetArray = response == null ? null : response.getJSONArray(realKey);
						if (targetArray == null) {
							targetArray = new JSONArray();
						}
						for (Object obj : array) {
							if (obj == null) {
								continue;
							}
							if (putType == 1) {
								if (targetArray.contains(obj)) {
									throw new ConflictException("PUT " + path + ", " + realKey + ":" + obj + " 已存在！");
								}
								targetArray.add(obj);
							} else if (putType == 2) {
								if (targetArray.contains(obj) == false) {
									throw new NullPointerException("PUT " + path + ", " + realKey + ":" + obj + " 不存在！");
								}
								targetArray.remove(obj);
							}
						}

						//add all 或 remove all >>>>>>>>>>>>>>>>>>>>>>>>>

						//PUT <<<<<<<<<<<<<<<<<<<<<<<<<
						transferredRequest.put(realKey, targetArray);
						//PUT >>>>>>>>>>>>>>>>>>>>>>>>>
					}
				} else {//JSONArray或其它Object，直接填充
					if (key.endsWith("@")) {//StringUtil.isPath((String) value)) {
						if (value instanceof String == false) {
							throw new IllegalArgumentException("\"key@\": 后面必须为依赖路径String！");
						}
						//						System.out.println("getObject  key.endsWith(@) >> parseRelation = " + parseRelation);
						String replaceKey = key.substring(0, key.length() - 1);//key{}@ getRealKey
						String targetPath = getValuePath(parentPath, new String((String) value));

						//先尝试获取，尽量保留缺省依赖路径，这样就不需要担心路径改变
						Object target = getValueByPath(targetPath);
						Log.i(TAG, "getObject targetPath = " + targetPath + "; target = " + target);

						if (target == null) {//String#equals(null)会出错
							Log.d(TAG, "getObject  target == null  >>  continue;");
							continue;
						}
						if (targetPath.equals(target)) {//必须valuePath和保证getValueByPath传进去的一致！
							Log.d(TAG, "getObject  targetPath.equals(target)  >>");

							//非查询关键词 @key 不影响查询，直接跳过
							if (isTableKey && (key.startsWith("@") == false || QueryConfig.TABLE_KEY_LIST.contains(key))) {
								Log.e(TAG, "getObject  isTableKey && (key.startsWith(@) == false"
										+ " || QueryConfig.TABLE_KEY_LIST.contains(key)) >>  return null;");
								return null;//获取不到就不用再做无效的query了。不考虑 Table:{Table:{}}嵌套
							} else {
								Log.d(TAG, "getObject  isTableKey(table) == false >> continue;");
								continue;//舍去，对Table无影响
							}
						} 


						//直接替换原来的key@:path为key:target
						Log.i(TAG, "getObject    >>  key = replaceKey; value = target;");
						key = replaceKey;
						value = target;
						Log.d(TAG, "getObject key = " + key + "; value = " + value);
					}

					if (key.endsWith("()")) {
						if (value instanceof String == false) {
							throw new IllegalArgumentException(path + "/" + key + "():function() 后面必须为函数String！");
						}
						functionMap.put(key, (String) value);
					} else if (key.startsWith("@") && QueryConfig.TABLE_KEY_LIST.contains(key) == false) {
						selfDefineKeyMap.put(key, value);
					} else {
						transferredRequest.put(key, value);
					}
				}
			}
		}


		boolean query = false;
		//执行SQL操作数据库
		if (isTableKey) {//提高性能
			query = true;

			QueryConfig config = newQueryConfig(table, transferredRequest).setCount(1);

			try {
				transferredRequest = getSQLObject(config);
			} catch (Exception e) {
				Log.e(TAG, "getObject  try { transferredRequest = getSQLObject(config2); } catch (Exception e) {");
				if (e instanceof NotExistException) {//非严重异常，有时候只是数据不存在
					//						e.printStackTrace();
					transferredRequest = null;//内部吃掉异常，put到最外层
					//						requestObject.put(JSONResponse.KEY_MESSAGE
					//								, StringUtil.getString(requestObject.get(JSONResponse.KEY_MESSAGE)
					//										+ "; query " + path + " cath NotExistException:"
					//										+ newErrorResult(e).getString(JSONResponse.KEY_MESSAGE)));
				} else {
					throw e;
				}
			}

			if (transferredRequest == null) {
				transferredRequest = new JSONObject(true);
			}
		}

		if (selfDefineKeyMap != null) {
			transferredRequest.putAll(selfDefineKeyMap);
		}
		if (functionMap != null) {
			if (query) {
				//解析函数function
				Set<String> functionSet = functionMap == null ? null : functionMap.keySet();
				if (functionSet != null && functionSet.isEmpty() == false) {
					for (String key : functionSet) {
						transferredRequest.put(getRealKey(requestMethod, key, false, false)
								, Function.invoke(transferredRequest, functionMap.get(key)));
					}
				}
			} else {
				transferredRequest.putAll(functionMap);
			}
		}

		if (query) {
			putQueryResult(path, transferredRequest);//解决获取关联数据时requestObject里不存在需要的关联数据
		}

		return transferredRequest;
	}


	/**
	 * TODO 一次获取QueryConfig的方式减少了count-1次JSONObject->QueryConfig的过程，大幅提升了性能。
	 * 但导致第一个Table位置被重新put到最后，并且需要重复getObject内的代码，目前[]中第一个Table还缺少对key()和自定义@key的支持。
	 * 评估下[]:{FirstTableKey:{Table}}第一个Table需要使用这两种功能符的场景、频率和替代方式(外层？)
	 */
	/**获取对象数组，该对象数组处于parentObject内
	 * @param parentPath parentObject的路径
	 * @param parentConfig 对子object的SQL查询配置，需要传两个层级
	 * @param name parentObject的key
	 * @param request parentObject的value
	 * @return 转为JSONArray不可行，因为会和被当成条件的key:JSONArray冲突。好像一般也就key{}:JSONArray用到??
	 * @throws Exception 
	 */
	private JSONArray getArray(String parentPath, String name, final JSONObject request) throws Exception {
		Log.i(TAG, "\n\n\n getArray parentPath = " + parentPath
				+ "; name = " + name + "; request = " + JSON.toJSONString(request));
		if (isHeadMethod(requestMethod, true)) {
			throw new UnsupportedOperationException("HEAD、POST_HEAD方法不允许重复查询！不应该传 " + name + " 等key[]:{}！");
		}
		if (request == null || request.isEmpty()) {//jsonKey-jsonValue条件
			return null;
		}
		String path = getAbsPath(parentPath, name);

		//不能改变，因为后面可能继续用到，导致1以上都改变 []:{0:{Comment[]:{0:{Comment:{}},1:{...},...}},1:{...},...}
		final int query = request.getIntValue(JSONRequest.KEY_QUERY);
		final int count = request.getIntValue(JSONRequest.KEY_COUNT);
		final int page = request.getIntValue(JSONRequest.KEY_PAGE);
		Log.d(TAG, "getArray  query = " + query + "; count = " + count + "; page = " + page);


		//最好先获取第一个table的所有项（where条件），填充一个列表？
		Set<String> set = new LinkedHashSet<>(request.keySet());
		if (set.isEmpty()) {//如果条件成立，说明所有的 parentPath/name:request 中request都无效！！！
			Log.e(TAG, "getArray  set.isEmpty() >> return null;");
			return null;
		}

		//不用total限制数量了，只用中断机制，total只在query = 1,2的时候才获取
		int size = 0;//在QUERY_TOTAL下不查询Table
		if (query != JSONRequest.QUERY_TOTAL) {
			size = count;//count为每页数量，size为第page页实际数量，max(size) = count
			if (size <= 0 || size > 100) {//size最大为100
				Log.d(TAG, "getArray  size <= 0 || size > 100 >> size = 100;");
				size = 100;
			}
		}



		//total<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
		//		if (query != JSONRequest.QUERY_TABLE) {

		JSONObject otherRequest = new JSONObject(true);

		boolean queryTotal = true;
		int total = 0;//满足条件的总数，忽略page,count
		Log.d(TAG, "getArray  query != JSONRequest.QUERY_TABLE >> ");
		String firstTableKey = null;
		String table;
		Object value;
		QueryConfig config = null;
		for (String key : set) {
			if (key == null) {
				continue;
			}

			if (queryTotal) {
				table = Pair.parseEntry(key, true).getKey();
				value = isTableKey(table) ? request.get(key) : null;
				if (value != null && value instanceof JSONObject) {// && value.isEmpty() == false) {
					queryTotal = false;

					firstTableKey = key;

					config = getConfigWithTotal(path, table, (JSONObject) value);
					total = config.getTotal();
				}
			}

			if (key.equals(firstTableKey) == false && QueryConfig.ARRAY_KEY_LIST.contains(key) == false) {
				otherRequest.put(key, request.get(key));
			}
		}
		if (total <= size*page) {
			Log.d(TAG, "getArray total <= size*page >> size = 0;");
			size = 0;//不再查询，但不能return null;因为还需要put条件
		}
		if (total > 0) {
			Log.d(TAG, "getArray  total = " + total + " >> putQueryResult(...)");
			putQueryResult(path + "/" + JSONResponse.KEY_TOTAL, total);//GET下替代HEAD的方式
		}
		//		}
		//total>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>

		Log.d(TAG, "getArray  size = " + size + "; page = " + page);

		//Table<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
		JSONArray response = new JSONArray();
		if (size > 0 && config != null) {//request内部没有JSONObject或者不存在适合条件的table内容
			config.setMethod(requestMethod).setCount(size).setPage(page);
			JSONObject parent;
			JSONObject first;
			//生成size个
			for (int i = 0; i < size; i++) {
				try {
					first = getSQLObject(config.setPosition(i));
				} catch (Exception e) {
					Log.e(TAG, "getArray  try { first = getSQLObject(config2); } catch (Exception e) {");
					if (e instanceof NotExistException) {//非严重异常，有时候只是数据不存在
						first = null;//内部吃掉异常，put到最外层
					} else {
						throw e;
					}
				}

				if (first == null || first.isEmpty()) {
					break;//数据库返回数量不够size，后面没有了。有依赖不为空，无依赖直接查询数据库。
				}
				putQueryResult(path + "/" + i + "/" + firstTableKey, first);

				parent = getObject(path, "" + i, otherRequest);//request);
				if (parent == null) {
					parent = new JSONObject(true);
				}
				parent.put(firstTableKey, first);//依赖的对象值在queryResultMap中取
				//				response.put("" + i, parent);
				response.add(parent);
			}
		}
		//Table>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>



		Log.i(TAG, "getArray  return response = \n" + JSON.toJSONString(response) + "\n>>>>>>>>>>>>>>>\n\n\n");

		return response;
	}

	//TODO 获取status和message，如果发生异常就throw new Exception(message!)，不行，不知道Exception类型，还是传boolean catchException到parse好
	/**估计最大总数，去掉value中所有依赖引用.
	 * TODO 返回一个{"total":10, name:value}更好，省去了之后的parseRelation
	 * @param path
	 * @param name
	 * @param value
	 * @return
	 * @throws Exception 
	 */
	public QueryConfig getConfigWithTotal(String path, String table, final JSONObject value) throws Exception {
		if (StringUtil.isNotEmpty(table, true) == false) {
			Log.e(TAG, "estimateMaxCount  StringUtil.isNotEmpty(table, true) == false >> return 0;");
			return null;
		}

		JSONObject request = new JSONObject(true);
		Set<Entry<String, Object>> entrySet = value == null ? null : new LinkedHashSet<>(value.entrySet());
		if (entrySet != null && entrySet.isEmpty() == false) {
			String k;
			Object v;
			Object target;
			//			String valid;
			for (Entry<String, Object> entry : entrySet) {
				k = entry == null ? "" : StringUtil.getString(entry.getKey());
				if (k.isEmpty() == false) {
					v = entry.getValue();
					if (k.endsWith("@")) {
						if (v == null) {
							continue;
						}

						String targetPath = getValuePath(path, new String((String) v));
						target = getValueByPath(targetPath);
						Log.i(TAG, "estimateMaxCount  k = "+ k + "; targetPath = " + targetPath + "; target = " + target);

						if (target == null || targetPath.equals(target)) {
							continue;
						}
						k = k.substring(0, k.length() - 1);
						v = target;

						//						value.put(k.substring(0, k.length() - 1), target);//解析依赖，后面就不用再解析了
					}

					//如果这些通不过，getObject时也通不过
					//					valid = new String(k);
					//					if (valid.endsWith("$")) {
					//						valid = valid.substring(0, valid.length() - 1);
					//					} else if (valid.endsWith("{}")) {
					//						valid = valid.substring(0, valid.length() - 2);
					//					} else if (valid.endsWith("<>")) {
					//						valid = valid.substring(0, valid.length() - 2);
					//					}
					//
					//					if (valid.endsWith("|") || valid.endsWith("&") || valid.endsWith("!")) {
					//						valid = valid.substring(0, valid.length() - 1);
					//					}
					//
					//					if (isWord(valid)) {
					request.put(k, v);//和value性质不同
					//					}
				}
			}
		}

		JSONObject response = new Parser(RequestMethod.HEAD).parseResponse(new JSONRequest(table, request));
		if (response != null) {
			response = response.getJSONObject(table);
		}

		return newQueryConfig(table, request).setTotal(response == null ? 0 : response.getIntValue(JSONResponse.KEY_COUNT));
	}




	/**获取被依赖引用的key的路径, 实时替换[] -> []/i
	 * @param parentPath
	 * @param valuePath
	 * @return
	 */
	private String getValuePath(String parentPath, String valuePath) {
		if (valuePath.startsWith(SEPARATOR)) {
			valuePath = getAbsPath(parentPath, valuePath);
		} else {//处理[] -> []/i
			valuePath = replaceArrayChildPath(parentPath, valuePath);
		}
		return valuePath;
	}

	/**获取绝对路径
	 * @param path
	 * @param name
	 * @return
	 */
	private String getAbsPath(String path, String name) {
		Log.i(TAG, "getPath  path = " + path + "; name = " + name + " <<<<<<<<<<<<<");
		path = StringUtil.getString(path);
		name = StringUtil.getString(name);
		if (StringUtil.isNotEmpty(path, false)) {
			if (StringUtil.isNotEmpty(name, false)) {
				path += ((name.startsWith(SEPARATOR) ? "" : SEPARATOR) + name);
			}
		} else {
			path = name;
		}
		if (path.startsWith(SEPARATOR)) {
			path = path.substring(1);
		}
		Log.i(TAG, "getPath  return " + path + " >>>>>>>>>>>>>>>>");
		return path;
	}

	/**替换[] -> []/i
	 * 不能写在getAbsPath里，因为name不一定是依赖路径
	 * @param parentPath
	 * @param valuePath
	 * @return
	 */
	private String replaceArrayChildPath(String parentPath, String valuePath) {
		String[] ps = StringUtil.split(parentPath, "]/");//"[]/");
		if (ps != null && ps.length > 1) {
			String[] vs = StringUtil.split(valuePath, "]/");

			if (vs != null && vs.length > 0) {
				String pos;
				for (int i = 0; i < ps.length - 1; i++) {
					if (ps[i] == null || ps[i].equals(vs[i]) == false) {//允许""？
						break;
					}

					pos = ps[i+1].contains("/") == false ? ps[i+1]
							: ps[i+1].substring(0, ps[i+1].indexOf("/"));
					if (
							//StringUtil.isNumer(pos) && 
							vs[i+1].startsWith(pos + "/") == false) {
						vs[i+1] = pos + "/" + vs[i+1];
					}
				}
				return StringUtil.getString(vs, "]/");
			}
		}
		return valuePath;
	}

	//依赖引用关系 <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<

	/**将已获取完成的object的内容替换requestObject里对应的值
	 * @param path object的路径
	 * @param result 需要被关联的object
	 */
	private synchronized void putQueryResult(String path, Object result) {
		Log.i(TAG, "\n putQueryResult  valuePath = " + path + "; result = " + result + "\n <<<<<<<<<<<<<<<<<<<<<<<");
		//		if (queryResultMap.containsKey(valuePath)) {//只保存被关联的value
		Log.d(TAG, "putQueryResult  queryResultMap.containsKey(valuePath) >> queryResultMap.put(path, result);");
		queryResultMap.put(path, result);
		//		}
	}
	/**根据路径获取值
	 * @param valuePath
	 * @return parent == null ? valuePath : parent.get(keys[keys.length - 1])
	 */
	private Object getValueByPath(String valuePath) {
		Log.i(TAG, "<<<<<<<<<<<<<<< \n getValueByPath  valuePath = " + valuePath + "\n <<<<<<<<<<<<<<<<<<");
		if (StringUtil.isEmpty(valuePath, true)) {
			Log.e(TAG, "getValueByPath  StringUtil.isNotEmpty(valuePath, true) == false >> return null;");
			return null;
		}
		Object target = queryResultMap.get(valuePath);
		if (target != null) {
			return target;
		}

		//取出key被valuePath包含的result，再从里面获取key对应的value
		Set<String> set = queryResultMap.keySet();
		JSONObject parent = null;
		String[] keys = null;
		for (String path : set) {
			if (valuePath.startsWith(path)) {
				try {
					parent = (JSONObject) queryResultMap.get(path);
				} catch (Exception e) {
					Log.e(TAG, "getValueByPath  try { parent = (JSONObject) queryResultMap.get(path); } catch { "
							+ "\n parent not instanceof JSONObject!");
					parent = null;
				}
				if (parent != null) {
					keys = StringUtil.splitPath(valuePath.substring(path.length()));
				}
				break;
			}
		}

		//逐层到达targetKey的直接容器JSONObject parent
		if (keys != null && keys.length > 1) {
			for (int i = 0; i < keys.length - 1; i++) {//一步一步到达指定位置parentPath
				if (parent == null) {//不存在或路径错误(中间的key对应value不是JSONObject)
					break;
				}
				parent = getJSONObject(parent, keys[i]);
			}
		}

		System.out.println("getValueByPath  return parent == null ? valuePath : parent.get(keys[keys.length - 1]); >> ");
		return parent == null ? valuePath : parent.get(keys[keys.length - 1]);
	}

	//依赖引用关系 >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>




	public static JSONObject getJSONObject(JSONObject object, String key) {
		try {
			return object.getJSONObject(key);
		} catch (Exception e) {
			Log.i(TAG, "getJSONObject  try { return object.getJSONObject(key);"
					+ " } catch (Exception e) { \n"  + e.getMessage());
		}
		return null;
	}


	/**获取数据库返回的String
	 * @param config
	 * @return
	 * @throws Exception
	 */
	private synchronized JSONObject getSQLObject(QueryConfig config) throws Exception {
		Log.i(TAG, "getSQLObject  config = " + JSON.toJSONString(config));
		AccessVerifier.verify(requestObject, config);
		return queryHelper.select(config);//QueryHelper2.getInstance().select(config);//
	}

	/**获取查询配置
	 * @param table
	 * @param request
	 * @return
	 */
	private QueryConfig newQueryConfig(String table, JSONObject request) {
		return QueryConfig.newQueryConfig(requestMethod, table, request);
	}


	private static final Pattern bigAlphaPattern = Pattern.compile("[A-Z]");
	private static final Pattern namePattern = Pattern.compile("^[0-9a-zA-Z_]+$");//已用55个中英字符测试通过

	/**判断是否为Array的key
	 * @param key
	 * @return
	 */
	public static boolean isArrayKey(String key) {
		return key != null && key.endsWith("[]");
	}
	/**判断是否为对应Table的key
	 * @param key
	 * @return
	 */
	public static boolean isTableKey(String key) {
		return isWord(key) && bigAlphaPattern.matcher(key.substring(0, 1)).matches();
	}
	/**判断是否为词，只能包含字母，数字或下划线
	 * @param key
	 * @return
	 */
	public static boolean isWord(String key) {
		return StringUtil.isNotEmpty(key, false) && namePattern.matcher(key).matches();
	}



	/**获取客户端实际需要的key
	 * <br> "userId@":"/User/id"      //@根据路径依赖，@始终在最后。value是'/'分隔的字符串。
	 * <br> "isPraised()":"isContain(Collection:idList,long:id)"  //()使用方法，value是方法表达式。不能与@并用。
	 * <br> "content$":"%searchKey%"  //$搜索，右边紧跟key。value是搜索表达式。
	 * <br> "@columns":"id,sex,name"  //关键字，左边紧跟key。暂时不用，因为目前关键字很少，几乎不会发生冲突。value是','分隔的字符串。
	 * 
	 * @param method
	 * @param originKey
	 * @param isTableKey
	 * @param saveLogic 保留逻辑运算符 & | !
	 * @return
	 */
	public static String getRealKey(RequestMethod method, String originKey, boolean isTableKey, boolean saveLogic)
			throws Exception {
		Log.i(TAG, "getRealKey  saveLogic = " + saveLogic + "; originKey = " + originKey);
		if (originKey == null || isArrayKey(originKey)) {
			Log.w(TAG, "getRealKey  originKey == null || isArrayKey(originKey) >>  return originKey;");
			return originKey;
		}

		String key = new String(originKey);
		if (key.endsWith("$")) {//搜索，查询时处理
			key = key.substring(0, key.length() - 1);
		} else if (key.endsWith("{}")) {//被包含，或者说key对应值处于value的范围内。查询时处理
			key = key.substring(0, key.length() - 2);
		} else if (key.endsWith("<>")) {//包含，或者说value处于key对应值的范围内。查询时处理
			key = key.substring(0, key.length() - 2);
		} else if (key.endsWith("()")) {//方法，查询完后处理，先用一个Map<key,function>保存？
			key = key.substring(0, key.length() - 2);
		} else if (key.endsWith("@")) {//引用，引用对象查询完后处理。fillTarget中暂时不用处理，因为非GET请求都是由给定的id确定，不需要引用
			key = key.substring(0, key.length() - 1);
		} else if (key.endsWith("+")) {//延长，PUT查询时处理
			if (method == PUT) {//不为PUT就抛异常
				key = key.substring(0, key.length() - 1);
			}
		} else if (key.endsWith("-")) {//缩减，PUT查询时处理
			if (method == PUT) {//不为PUT就抛异常
				key = key.substring(0, key.length() - 1);
			}
		}

		String last = null;
		if (isGetMethod(method, true) || isHeadMethod(method, true)) {//逻辑运算符仅供GET,HEAD方法使用
			last = key.isEmpty() ? "" : key.substring(key.length() - 1);
			if ("&".equals(last) || "|".equals(last) || "!".equals(last)) {
				key = key.substring(0, key.length() - 1);
			} else {
				last = null;//避免key + StringUtil.getString(last)错误延长
			}
		}

		//"User:toUser":User转换"toUser":User, User为查询同名Table得到的JSONObject。交给客户端处理更好
		if (isTableKey) {//不允许在column key中使用Type:key形式
			key = Pair.parseEntry(key, true).getKey();//table以左边为准
		} else {
			key = Pair.parseEntry(key).getValue();//column以右边为准
		}

		if (isWord(key.startsWith("@") ? key.substring(1) : key) == false) {
			throw new IllegalArgumentException(TAG + "/" + method + "  getRealKey: 字符 " + originKey + " 不合法！");
		}

		if (saveLogic) {
			key = key + StringUtil.getString(last);
		}
		Log.i(TAG, "getRealKey  return key = " + key);
		return key;
	}

}
