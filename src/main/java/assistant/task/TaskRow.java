package assistant.task;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.apache.ibatis.annotations.Mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.time.OffsetDateTime;

@TableName("tasks")
public class TaskRow {
    @TableId public String id;
    public String type;
    public String input;
    public String scope;
    public String status;
    public String step;
    public int attempts;
    public String resultType;
    public String resultId;
    public String error;
    public OffsetDateTime updatedAt;
}

@Mapper
interface TaskMapper extends BaseMapper<TaskRow> {}
