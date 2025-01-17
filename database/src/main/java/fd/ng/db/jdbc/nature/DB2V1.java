package fd.ng.db.jdbc.nature;

import fd.ng.core.exception.internal.FrameworkRuntimeException;

public class DB2V1 extends AbstractNatureDatabase {
	private DB2V1() {}

	public static PagedSqlInfo toPagedSql(String sql, int orginBegin, int orginEnd) {
		if(orginBegin<1) throw new FrameworkRuntimeException("page begin must greater than 0");
		PagedSqlInfo pagedSqlInfo = new PagedSqlInfo();

		if(orginBegin>1) {
			StringBuilder _pagedSql = new StringBuilder( sql.length()+250 );
			_pagedSql.append( "select * from ( select inner2_.*, rownumber() over(order by order of inner2_) as rownumber_ from ( ")
					.append( sql )
					.append( " fetch first ?" )//limit
					.append( " rows only ) as inner2_ ) as inner1_ where rownumber_ > ?" )//offset
					.append( " order by rownumber_" );
			pagedSqlInfo.setSql(_pagedSql.toString());
			pagedSqlInfo.setPageNo1(orginEnd);
			pagedSqlInfo.setPageNo2(orginBegin);
		} else {
			pagedSqlInfo.setSql(sql + " fetch first ? rows only");
			pagedSqlInfo.setPageNo1(orginEnd);
			pagedSqlInfo.setPageNo2(PagedSqlInfo.PageNoValue_NotExist);
		}
		return pagedSqlInfo;
	}
}
