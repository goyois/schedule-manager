package com.example.schedule_manager.domain.mandalart.entity;

import com.example.schedule_manager.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(
        name = "mandalart_cells",
        uniqueConstraints = @UniqueConstraint(columnNames = {"mandalart_board_id", "row_index", "col_index"})
)
public class MandalartCell extends BaseEntity {

    // Schedule.id와 동일한 이유/제약(IDENTITY는 JDBC 배치 INSERT를 막고, 그 내부 시퀀스는
    // information_schema.sequences에서 스펙상 제외돼 ddl-auto: validate와 충돌한다 - Schedule.id의
    // 주석 참고) - MandalartService.createBoard가 보드 하나당 81개 셀을 saveAll로 한 번에 저장한다.
    // 별도 시퀀스(mandalart_cell_id_seq_v2)를 새로 만들어 기존 시퀀스보다 충분히 위에서 시작하게 했다
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "mandalart_cell_id_generator")
    @SequenceGenerator(name = "mandalart_cell_id_generator", sequenceName = "mandalart_cell_id_seq_v2", allocationSize = 50)
    @Column(name = "mandalart_cell_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mandalart_board_id", nullable = false)
    private MandalartBoard board;

    // 보드 하나당 9x9 = 81개 셀이 row/col 0~8 좌표로 고정 생성된다 (MandalartService.createBoard 참고).
    // 좌표는 생성 후 불변 — update() 는 content 만 바꾼다
    @Column(name = "row_index", nullable = false)
    private int rowIndex;

    @Column(name = "col_index", nullable = false)
    private int colIndex;

    private String content;

    public void update(String content) {
        this.content = content;
    }
}
